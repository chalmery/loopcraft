package io.github.chalmery.loopcraft.agent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.github.chalmery.loopcraft.agent.config.AgentProperties;
import io.github.chalmery.loopcraft.agent.core.Agent;
import io.github.chalmery.loopcraft.agent.dto.AgentViews;
import io.github.chalmery.loopcraft.agent.core.AgentRequest;
import io.github.chalmery.loopcraft.agent.model.AgentRunEvent;
import io.github.chalmery.loopcraft.agent.core.RunBudget;
import io.github.chalmery.loopcraft.agent.core.RunListener;
import io.github.chalmery.loopcraft.agent.core.RunOutcome;
import io.github.chalmery.loopcraft.identity.model.UserAccount;
import io.github.chalmery.loopcraft.identity.service.IdentityService;
import io.github.chalmery.loopcraft.llm.service.ChatModelFactory;
import io.github.chalmery.loopcraft.template.mapper.AgentTemplateMapper;
import io.github.chalmery.loopcraft.template.model.AgentTemplate;
import io.github.chalmery.loopcraft.tool.SkillTools;

import jakarta.annotation.PreDestroy;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * chat 编排：受理时同步校验成员与模板链路并落运行行，循环在后台执行，事件经 RunEvents
 * 双层通道（数据库真相源 + Redis 热通道）推送；SSE 订阅断线可按事件序号重放。
 */
@Service
public class AgentChatService {

    /** 热通道缺失时数据库轮询兜底的间隔。 */
    private static final long POLL_INTERVAL_MS = 500;

    private final IdentityService identity;

    private final ObjectProvider<AgentTemplateMapper> templateMappers;

    private final ObjectProvider<StringRedisTemplate> redisTemplates;

    private final ChatModelFactory chatModelFactory;

    private final SkillTools skillTools;

    private final Agent agent;

    private final RunEvents runEvents;

    private final AgentProperties properties;

    private final ExecutorService runs = Executors.newVirtualThreadPerTaskExecutor();

    public AgentChatService(
            IdentityService identity,
            ObjectProvider<AgentTemplateMapper> templateMappers,
            ObjectProvider<StringRedisTemplate> redisTemplates,
            ChatModelFactory chatModelFactory,
            SkillTools skillTools,
            Agent agent,
            RunEvents runEvents,
            AgentProperties properties) {
        this.identity = identity;
        this.templateMappers = templateMappers;
        this.redisTemplates = redisTemplates;
        this.chatModelFactory = chatModelFactory;
        this.skillTools = skillTools;
        this.agent = agent;
        this.runEvents = runEvents;
        this.properties = properties;
    }

    @PreDestroy
    void shutdown() {
        runs.shutdown();
    }

    /** 所有租户成员可发起；链路校验通过即受理（202），循环异步执行至终态。 */
    public AgentViews.RunStarted start(
            UserAccount actor, long tenantId, long templateId, String message) {
        if (redisTemplates.getIfAvailable() == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Agent 运行通道需要启用 Redis");
        }
        identity.tenant(actor, tenantId, false);
        AgentTemplate template = template(tenantId, templateId);
        Map<String, ToolCallback> tools = skillTools.forTemplate(tenantId, templateId);
        ChatModelFactory.Prepared prepared = chatModelFactory.prepare(tenantId, template, tools);
        RunBudget budget = budget(template);
        String runId = UUID.randomUUID().toString();
        runEvents.startRun(tenantId, runId, templateId, actor.getId());
        AgentRequest request =
                new AgentRequest(
                        message,
                        template.getSystemPrompt(),
                        prepared.chatModel(),
                        prepared.options(),
                        tools,
                        budget);
        int maxIterations = budget.maxIterations();
        runs.execute(() -> execute(runId, request, maxIterations));
        return new AgentViews.RunStarted(runId);
    }

    /** SSE 订阅：先按 Last-Event-ID 重放数据库事件，再接热通道实时推送；连接断开不取消任务。 */
    public SseEmitter subscribe(UserAccount actor, long tenantId, String runId, Long lastEventId) {
        identity.tenant(actor, tenantId, false);
        if (runEvents.run(tenantId, runId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前租户无此运行");
        }
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean stopped = new AtomicBoolean(false);
        emitter.onCompletion(() -> stopped.set(true));
        emitter.onError(error -> stopped.set(true));
        emitter.onTimeout(() -> stopped.set(true));
        long fromSeq = lastEventId == null ? 0 : lastEventId;
        runs.execute(() -> pump(emitter, runId, fromSeq, stopped));
        return emitter;
    }

    private void execute(String runId, AgentRequest request, int maxIterations) {
        RunOutcome outcome = agent.run(request, new RunEventPublisher(runEvents, runId, maxIterations));
        runEvents.finishRun(runId, outcome);
    }

    /** 订阅推送循环：数据库重放 → 热通道实时 → 通道缺失时数据库轮询，终态事件后收尾。 */
    private void pump(SseEmitter emitter, String runId, long fromSeq, AtomicBoolean stopped) {
        long lastSeq = fromSeq;
        for (AgentRunEvent event : runEvents.history(runId, lastSeq)) {
            if (stopped.get()) {
                return;
            }
            if (!send(emitter, event.getId(), event.getType(), event.getPayload())) {
                return;
            }
            lastSeq = event.getId();
            if ("run_finished".equals(event.getType())) {
                emitter.complete();
                return;
            }
        }
        String offset = "$";
        while (!stopped.get()) {
            for (RunEvents.StreamEntry entry : runEvents.readStream(runId, offset, Duration.ofSeconds(5))) {
                offset = entry.recordId();
                if (entry.seq() <= lastSeq) {
                    continue;
                }
                if (!send(emitter, entry.seq(), entry.type(), entry.payload())) {
                    return;
                }
                lastSeq = entry.seq();
                if ("run_finished".equals(entry.type())) {
                    emitter.complete();
                    return;
                }
            }
            if (runEvents.streamAlive(runId)) {
                continue;
            }
            pollDatabase(emitter, runId, lastSeq, stopped);
            return;
        }
    }

    /** 热通道缺失（故障或过期）时按序号轮询数据库，直到终态或连接断开。 */
    private void pollDatabase(
            SseEmitter emitter, String runId, long fromSeq, AtomicBoolean stopped) {
        long lastSeq = fromSeq;
        while (!stopped.get()) {
            sleepQuietly(POLL_INTERVAL_MS);
            if (stopped.get()) {
                return;
            }
            for (AgentRunEvent event : runEvents.history(runId, lastSeq)) {
                if (!send(emitter, event.getId(), event.getType(), event.getPayload())) {
                    return;
                }
                lastSeq = event.getId();
                if ("run_finished".equals(event.getType())) {
                    emitter.complete();
                    return;
                }
            }
        }
    }

    private boolean send(SseEmitter emitter, long seq, String type, String payload) {
        try {
            emitter.send(
                    SseEmitter.event()
                            .id(Long.toString(seq))
                            .name(type)
                            .data(payload, MediaType.APPLICATION_JSON));
            return true;
        } catch (Exception error) {
            // 客户端断开或连接 IO 失败；任务继续执行，重连后按序号重放。
            return false;
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private AgentTemplate template(long tenantId, long templateId) {
        AgentTemplate template =
                templateMappers.getObject()
                        .selectOne(
                                Wrappers.<AgentTemplate>lambdaQuery()
                                        .eq(AgentTemplate::getTenantId, tenantId)
                                        .eq(AgentTemplate::getId, templateId));
        if (template == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前租户无此模板");
        }
        if (!Boolean.TRUE.equals(template.getEnabled())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模板已停用");
        }
        return template;
    }

    /** 模型轮次与总时长来自模板必填字段；0 视为配置错误拒绝，不进入循环。 */
    private RunBudget budget(AgentTemplate template) {
        Long maxIterations = template.getMaxIterations();
        BigDecimal timeoutMinutes = template.getTimeoutMinutes();
        if (maxIterations == null || maxIterations < 1 || timeoutMinutes == null
                || timeoutMinutes.signum() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "模板运行参数无效，请修正迭代与超时设置");
        }
        long runTimeoutNanos;
        try {
            runTimeoutNanos =
                    timeoutMinutes.multiply(BigDecimal.valueOf(60_000_000_000L)).longValueExact();
        } catch (ArithmeticException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模板超时设置过大");
        }
        return new RunBudget(
                maxIterations.intValue(),
                Duration.ofNanos(runTimeoutNanos),
                properties.modelRequestTimeout(),
                properties.toolTimeout(),
                properties.maxToolCalls());
    }

    /** 循环事件到通道事件的适配；事件只带标识与计数，不含工具参数与结果内容。 */
    private record RunEventPublisher(RunEvents events, String runId, int maxIterations)
            implements RunListener {

        @Override
        public void iterationStarted(int iteration) {
            events.publish(
                    runId,
                    "iteration_started",
                    Map.of("iteration", iteration, "maxIterations", maxIterations));
        }

        @Override
        public void toolCalled(String callId, String name) {
            events.publish(runId, "tool_called", Map.of("callId", callId, "name", name));
        }

        @Override
        public void toolFinished(
                String callId, String name, boolean ok, boolean truncated, long durationMs) {
            events.publish(
                    runId,
                    "tool_finished",
                    Map.of(
                            "callId", callId,
                            "name", name,
                            "ok", ok,
                            "truncated", truncated,
                            "durationMs", durationMs));
        }
    }
}
