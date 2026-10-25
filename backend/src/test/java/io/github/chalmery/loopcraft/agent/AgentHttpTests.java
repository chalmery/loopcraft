package io.github.chalmery.loopcraft.agent;

import io.github.chalmery.loopcraft.agent.core.Agent;
import io.github.chalmery.loopcraft.agent.core.AgentRequest;
import io.github.chalmery.loopcraft.agent.core.RunBudget;
import io.github.chalmery.loopcraft.agent.core.RunListener;
import io.github.chalmery.loopcraft.agent.core.RunOutcome;
import io.github.chalmery.loopcraft.agent.core.RunStatus;
import io.github.chalmery.loopcraft.agent.model.AgentRun;
import io.github.chalmery.loopcraft.agent.model.AgentRunEvent;
import io.github.chalmery.loopcraft.agent.service.AgentChatService;
import io.github.chalmery.loopcraft.agent.service.RunEvents;
import io.github.chalmery.loopcraft.agent.config.AgentProperties;
import io.github.chalmery.loopcraft.identity.model.SessionUser;
import io.github.chalmery.loopcraft.identity.model.UserAccount;
import io.github.chalmery.loopcraft.identity.service.IdentityService;
import io.github.chalmery.loopcraft.llm.service.ChatModelFactory;
import io.github.chalmery.loopcraft.template.mapper.AgentTemplateMapper;
import io.github.chalmery.loopcraft.template.model.AgentTemplate;
import io.github.chalmery.loopcraft.tool.SkillTools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Agent 接口级测试：受理 202、链路校验失败映射与 SSE 订阅（重放、热通道、轮询兜底）。
 * 数据库与 Redis 以 Mockito 替身隔离，验证 AgentChatService 的编排行为本身。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AgentHttpTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final long TENANT = 1L;

    private static final long TEMPLATE_ID = 5L;

    @Autowired private MockMvc mvc;

    @MockitoBean private IdentityService identity;

    @MockitoBean private AgentTemplateMapper templates;

    @MockitoBean private StringRedisTemplate redis;

    @MockitoBean private ChatModelFactory chatModelFactory;

    @MockitoBean private SkillTools skillTools;

    @MockitoBean private Agent agent;

    @MockitoBean private RunEvents runEvents;

    private UserAccount actor;

    @BeforeEach
    void setup() {
        actor = new UserAccount(7L, "tester", null, "测试用户", false, true, 0);
        // 授权检查与控制器都会经过 current；模拟真实会话校验，匿名或非法主体按未登录处理。
        when(identity.current(any()))
                .thenAnswer(
                        invocation -> {
                            Authentication authentication = invocation.getArgument(0);
                            if (authentication == null
                                    || !authentication.isAuthenticated()
                                    || !(authentication.getPrincipal() instanceof SessionUser)) {
                                throw new BadCredentialsException("请先登录");
                            }
                            return actor;
                        });
        when(skillTools.forTemplate(TENANT, TEMPLATE_ID)).thenReturn(Map.of());
        when(chatModelFactory.prepare(eq(TENANT), any(), any()))
                .thenReturn(
                        new ChatModelFactory.Prepared(
                                mock(ChatModel.class), OpenAiChatOptions.builder().build()));
        when(agent.run(any(), any()))
                .thenAnswer(
                        invocation -> {
                            RunListener listener = invocation.getArgument(1);
                            listener.iterationStarted(1);
                            listener.toolCalled("c1", "read_skill");
                            listener.toolFinished("c1", "read_skill", true, false, 25);
                            return RunOutcome.completed("最终答案", 1, 1);
                        });
    }

    @Test
    void chatAcceptedReturns202WithRunIdAndRunsToTerminalState() throws Exception {
        when(templates.selectOne(any())).thenReturn(template(6L, "2"));

        String body =
                mvc.perform(
                                post("/api/tenants/1/agent/chat")
                                        .with(authentication(auth()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"templateId\":5,\"message\":\"请帮我查技能\"}"))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.code").value(202))
                        .andExpect(jsonPath("$.message").value("已受理"))
                        .andExpect(jsonPath("$.data.runId").isNotEmpty())
                        .andExpect(jsonPath("$.traceId").isNotEmpty())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String runId = JSON.readTree(body).path("data").path("runId").asString();

        verify(runEvents).startRun(TENANT, runId, TEMPLATE_ID, 7L);
        ArgumentCaptor<AgentRequest> requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agent, timeout(2000)).run(requestCaptor.capture(), any(RunListener.class));
        AgentRequest request = requestCaptor.getValue();
        assertThat(request.message()).isEqualTo("请帮我查技能");
        assertThat(request.systemPrompt()).isEqualTo("系统提示词");
        // 压缩字段不参与首版运行：预算只来自模板 maxIterations/timeoutMinutes 与全局配置。
        RunBudget budget = request.budget();
        assertThat(budget.maxIterations()).isEqualTo(6);
        assertThat(budget.runTimeout()).isEqualTo(Duration.ofMinutes(2));
        assertThat(budget.modelRequestTimeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(budget.toolTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(budget.maxToolCalls()).isEqualTo(32);

        ArgumentCaptor<RunOutcome> outcomeCaptor = ArgumentCaptor.forClass(RunOutcome.class);
        verify(runEvents, timeout(2000)).finishRun(eq(runId), outcomeCaptor.capture());
        assertThat(outcomeCaptor.getValue().status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishedEventsCarryOnlyIdentifiersAndCounters() throws Exception {
        when(templates.selectOne(any())).thenReturn(template(6L, "2"));

        String body =
                mvc.perform(
                                post("/api/tenants/1/agent/chat")
                                        .with(authentication(auth()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"templateId\":5,\"message\":\"你好\"}"))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String runId = JSON.readTree(body).path("data").path("runId").asString();

        var outcomeCaptor = ArgumentCaptor.forClass(RunOutcome.class);
        verify(runEvents, timeout(2000)).finishRun(eq(runId), outcomeCaptor.capture());
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Map> payloads = ArgumentCaptor.forClass(Map.class);
        verify(runEvents, atLeastOnce()).publish(eq(runId), anyString(), payloads.capture());
        // 事件只允许标识与计数，不携带用户消息、工具参数或工具结果内容。
        Set<String> allowed =
                Set.of(
                        "templateId",
                        "iteration",
                        "maxIterations",
                        "callId",
                        "name",
                        "ok",
                        "truncated",
                        "durationMs",
                        "status",
                        "answer",
                        "reason",
                        "iterations",
                        "toolCalls");
        for (Object raw : payloads.getAllValues()) {
            Map<String, Object> payload = (Map<String, Object>) raw;
            assertThat(payload.keySet()).isSubsetOf(allowed);
        }
    }

    @Test
    void missingTemplateForTenantReturns404WithoutEchoingPrompt() throws Exception {
        when(templates.selectOne(any())).thenReturn(null);

        mvc.perform(
                        post("/api/tenants/1/agent/chat")
                                .with(authentication(auth()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"templateId\":5,\"message\":\"秘密提示词XYZ\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("当前租户无此模板"))
                .andExpect(
                        result ->
                                assertThat(result.getResponse().getContentAsString())
                                        .doesNotContain("秘密提示词XYZ"));
        verifyNoInteractions(agent);
        verify(runEvents, never()).startRun(anyLong(), anyString(), anyLong(), anyLong());
    }

    @Test
    void disabledTemplateReturns503() throws Exception {
        AgentTemplate disabled = template(6L, "2");
        disabled.setEnabled(false);
        when(templates.selectOne(any())).thenReturn(disabled);

        mvc.perform(chatRequest())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("模板已停用"));
        verifyNoInteractions(agent);
    }

    @Test
    void invalidTemplateBudgetIsRejectedBeforeRun() throws Exception {
        AgentTemplate zeroIterations = template(0L, "2");
        when(templates.selectOne(any())).thenReturn(zeroIterations);
        mvc.perform(chatRequest())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("模板运行参数无效，请修正迭代与超时设置"));

        AgentTemplate zeroTimeout = template(6L, "0");
        when(templates.selectOne(any())).thenReturn(zeroTimeout);
        mvc.perform(chatRequest())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("模板运行参数无效，请修正迭代与超时设置"));

        verifyNoInteractions(agent, runEvents);
    }

    @Test
    void modelChainFailurePropagatesWithoutFallback() throws Exception {
        when(templates.selectOne(any())).thenReturn(template(6L, "2"));
        when(chatModelFactory.prepare(eq(TENANT), any(), any()))
                .thenThrow(
                        new ResponseStatusException(
                                HttpStatus.SERVICE_UNAVAILABLE, "模板引用的模型不存在或已停用"));

        mvc.perform(chatRequest())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("模板引用的模型不存在或已停用"));
        verifyNoInteractions(agent);
        verify(runEvents, never()).startRun(anyLong(), anyString(), anyLong(), anyLong());
    }

    @Test
    void chatRequiresAuthenticationAndCsrf() throws Exception {
        // POST 未认证时先补 CSRF，让请求落到身份检查得到 401，而不是被 CSRF 拦截为 403。
        mvc.perform(
                        post("/api/tenants/1/agent/chat")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"templateId\":5,\"message\":\"你好\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/tenants/1/agent/chat")
                                .with(authentication(auth()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"templateId\":5,\"message\":\"你好\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void chatRejectedWhenRedisChannelUnavailable() {
        // 直接构造服务实例：ObjectProvider 未提供 StringRedisTemplate 时受理必须 503，且不触发链路校验。
        IdentityService identityMock = mock(IdentityService.class);
        AgentTemplateMapper templatesMock = mock(AgentTemplateMapper.class);
        ChatModelFactory factoryMock = mock(ChatModelFactory.class);
        SkillTools skillToolsMock = mock(SkillTools.class);
        Agent agentMock = mock(Agent.class);
        RunEvents runEventsMock = mock(RunEvents.class);
        AgentChatService service =
                new AgentChatService(
                        identityMock,
                        providerReturning(templatesMock),
                        providerReturning((StringRedisTemplate) null),
                        factoryMock,
                        skillToolsMock,
                        agentMock,
                        runEventsMock,
                        defaultProperties());

        assertThatThrownBy(
                        () -> service.start(actor, TENANT, TEMPLATE_ID, "你好"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Agent 运行通道需要启用 Redis");
        verifyNoInteractions(identityMock, factoryMock, skillToolsMock, agentMock, runEventsMock);
    }

    @Test
    void subscribeReturns404ForUnknownOrForeignRun() throws Exception {
        when(runEvents.run(TENANT, "known-run")).thenReturn(new AgentRun());
        when(runEvents.run(TENANT, "missing-run")).thenReturn(null);
        when(runEvents.run(2L, "known-run")).thenReturn(null);

        // event-stream 内容协商下错误体不是标准 ApiResponse JSON，核心断言是状态码与不泄露事件内容。
        mvc.perform(events("missing-run", null))
                .andExpect(status().isNotFound())
                .andExpect(
                        result ->
                                assertThat(result.getResponse().getContentAsString())
                                        .doesNotContain("run_finished"));
        // 同一 runId 在其他租户路径下不可见，等价于不存在。
        mvc.perform(
                        get("/api/tenants/2/agent/runs/known-run/events")
                                .accept(MediaType.TEXT_EVENT_STREAM)
                                .with(authentication(auth())))
                .andExpect(status().isNotFound())
                .andExpect(
                        result ->
                                assertThat(result.getResponse().getContentAsString())
                                        .doesNotContain("run_finished"));
    }

    @Test
    void sseReplaysHistoryFromLastEventIdAndCompletes() throws Exception {
        String runId = "replay-run";
        when(runEvents.run(TENANT, runId)).thenReturn(new AgentRun());
        when(runEvents.history(runId, 3L))
                .thenReturn(
                        List.of(
                                event(4L, "iteration_started", "{\"iteration\":1}"),
                                event(5L, "run_finished", "{\"status\":\"COMPLETED\"}")));

        String body = sse(runId, "3");

        assertThat(body).contains("id:4\n").contains("event:iteration_started\n");
        assertThat(body).contains("id:5\n").contains("event:run_finished\n");
        assertThat(body).doesNotContain("id:3\n");
        verify(runEvents).history(runId, 3L);
    }

    @Test
    void sseStreamsNewEntriesFromHotChannelAndSkipsSeen() throws Exception {
        String runId = "hot-run";
        when(runEvents.run(TENANT, runId)).thenReturn(new AgentRun());
        when(runEvents.history(runId, 1L)).thenReturn(List.of());
        when(runEvents.readStream(eq(runId), eq("$"), any()))
                .thenReturn(
                        List.of(
                                entry("1-1", 1L, "iteration_started", "{\"iteration\":1}"),
                                entry("2-1", 2L, "tool_called", "{\"name\":\"read_skill\"}"),
                                entry("3-1", 3L, "run_finished", "{\"status\":\"COMPLETED\"}")));

        String body = sse(runId, "1");

        assertThat(body).contains("id:2\n").contains("id:3\n");
        assertThat(body).doesNotContain("id:1\n");
    }

    @Test
    void sseFallsBackToDatabasePollingWhenHotChannelMissing() throws Exception {
        String runId = "poll-run";
        when(runEvents.run(TENANT, runId)).thenReturn(new AgentRun());
        AtomicInteger queries = new AtomicInteger();
        when(runEvents.history(eq(runId), eq(0L)))
                .thenAnswer(
                        invocation ->
                                queries.incrementAndGet() > 1
                                        ? List.of(
                                                event(
                                                        1L,
                                                        "run_finished",
                                                        "{\"status\":\"COMPLETED\"}"))
                                        : List.of());
        when(runEvents.readStream(anyString(), anyString(), any())).thenReturn(List.of());
        when(runEvents.streamAlive(runId)).thenReturn(false);

        String body = sse(runId, null);

        assertThat(body).contains("id:1\n").contains("event:run_finished\n");
    }

    private org.springframework.security.core.Authentication auth() {
        return UsernamePasswordAuthenticationToken.authenticated(
                new SessionUser(actor.getId(), 0), null, List.of());
    }

    private MockHttpServletRequestBuilder chatRequest() {
        return post("/api/tenants/1/agent/chat")
                .with(authentication(auth()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"templateId\":5,\"message\":\"你好\"}");
    }

    private MockHttpServletRequestBuilder events(String runId, String lastEventId) {
        MockHttpServletRequestBuilder request =
                get("/api/tenants/1/agent/runs/" + runId + "/events")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .with(authentication(auth()));
        if (lastEventId != null) {
            request.header("Last-Event-ID", lastEventId);
        }
        return request;
    }

    /** 完成 SSE 订阅：等待订阅循环把 emitter 收尾后取回整段事件流文本。 */
    private String sse(String runId, String lastEventId) throws Exception {
        MvcResult result = mvc.perform(events(runId, lastEventId)).andReturn();
        result.getAsyncResult(5000);
        return mvc.perform(asyncDispatch(result))
                .andReturn()
                .getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private AgentTemplate template(Long maxIterations, String timeoutMinutes) {
        AgentTemplate template = new AgentTemplate();
        template.setId(TEMPLATE_ID);
        template.setTenantId(TENANT);
        template.setName("测试模板");
        template.setEnabled(true);
        template.setModelId(11L);
        template.setSystemPrompt("系统提示词");
        template.setTemperature(null);
        // 压缩字段即使填写也不参与首版运行。
        template.setContextBudgetTokens(32768L);
        template.setSummaryModelId(99L);
        template.setKeepRecentMessages(12);
        template.setMaxIterations(maxIterations);
        template.setTimeoutMinutes(new BigDecimal(timeoutMinutes));
        return template;
    }

    private static AgentRunEvent event(long seq, String type, String payload) {
        AgentRunEvent event = new AgentRunEvent();
        event.setId(seq);
        event.setRunId("run");
        event.setType(type);
        event.setPayload(payload);
        event.setCreatedAt(OffsetDateTime.now());
        return event;
    }

    private static RunEvents.StreamEntry entry(
            String recordId, long seq, String type, String payload) {
        return new RunEvents.StreamEntry(recordId, seq, type, payload);
    }

    private static AgentProperties defaultProperties() {
        return new AgentProperties(
                Duration.ofSeconds(45),
                Duration.ofSeconds(15),
                32,
                8000,
                1000,
                Duration.ofMinutes(10));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerReturning(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(bean);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }
}
