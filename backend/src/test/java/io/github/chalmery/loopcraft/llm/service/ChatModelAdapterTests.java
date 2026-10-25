package io.github.chalmery.loopcraft.llm.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.chalmery.loopcraft.agent.config.AgentProperties;
import io.github.chalmery.loopcraft.agent.core.Agent;
import io.github.chalmery.loopcraft.agent.core.AgentRequest;
import io.github.chalmery.loopcraft.agent.core.RunBudget;
import io.github.chalmery.loopcraft.agent.core.RunFailure;
import io.github.chalmery.loopcraft.agent.core.RunListener;
import io.github.chalmery.loopcraft.agent.core.RunOutcome;
import io.github.chalmery.loopcraft.agent.core.RunStatus;
import io.github.chalmery.loopcraft.llm.mapper.LlmModelMapper;
import io.github.chalmery.loopcraft.llm.mapper.LlmSourceMapper;
import io.github.chalmery.loopcraft.llm.model.LlmModel;
import io.github.chalmery.loopcraft.llm.model.LlmSource;
import io.github.chalmery.loopcraft.skill.mapper.SkillMapper;
import io.github.chalmery.loopcraft.skill.mapper.SkillVersionMapper;
import io.github.chalmery.loopcraft.skill.model.Skill;
import io.github.chalmery.loopcraft.skill.model.SkillVersion;
import io.github.chalmery.loopcraft.skill.service.SkillPackages;
import io.github.chalmery.loopcraft.storage.model.FileDownload;
import io.github.chalmery.loopcraft.storage.model.ObjectLocation;
import io.github.chalmery.loopcraft.storage.model.StoredFile;
import io.github.chalmery.loopcraft.storage.service.FileStorage;
import io.github.chalmery.loopcraft.template.mapper.AgentTemplateMapper;
import io.github.chalmery.loopcraft.template.mapper.TemplateSkillMapper;
import io.github.chalmery.loopcraft.template.model.AgentTemplate;
import io.github.chalmery.loopcraft.template.model.TemplateSkill;
import io.github.chalmery.loopcraft.tool.SkillTools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OpenAI 兼容适配器离线桩测试：本地 HTTP 桩承接真实 ChatModelFactory → OpenAiChatModel →
 * Agent 循环链路，覆盖 finish reason 入站映射、工具 schema 出站、消息回放与取消/超时，
 * 不请求真实模型。
 */
class ChatModelAdapterTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String ENCRYPTION_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private HttpServer server;

    private final List<RecordedRequest> requests = new ArrayList<>();

    private final ConcurrentLinkedQueue<StubResponse> responses = new ConcurrentLinkedQueue<>();

    private final BlockingControl blocking = new BlockingControl();

    private final MemoryStorage objects = new MemoryStorage();

    private String baseUrl;

    @BeforeEach
    void startStub() throws IOException {
        requests.clear();
        responses.clear();
        blocking.reset();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stopStub() {
        blocking.release();
        server.stop(0);
    }

    @Test
    void stopFinishMapsToCompletedAndOutboundCarriesSchemaAndCredential() {
        responses.add(StubResponse.of(textCompletion("stop", "桩模型回答")));

        RunOutcome outcome = runOnce(defaultBudget());

        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.answer()).isEqualTo("桩模型回答");
        RecordedRequest outbound = requests.get(0);
        assertThat(outbound.authorization()).isEqualTo("Bearer sk-stub-key");
        JsonNode body = JSON.readTree(outbound.body());
        assertThat(body.path("model").asString()).isEqualTo("it-stub-model");
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.7d);
        // 出站工具表来自模板技能绑定，包含名称、描述与 JSON Schema 参数定义。
        List<String> names = new ArrayList<>();
        for (JsonNode tool : body.path("tools")) {
            names.add(tool.path("function").path("name").asString());
            assertThat(tool.path("function").path("description").asString()).isNotEmpty();
            assertThat(tool.path("function").path("parameters").path("type").asString())
                    .isEqualTo("object");
        }
        assertThat(names).containsExactlyInAnyOrder("list_skills", "read_skill");
        JsonNode messages = body.path("messages");
        assertThat(messages.get(0).path("role").asString()).isEqualTo("system");
        assertThat(messages.get(1).path("role").asString()).isEqualTo("user");
    }

    @Test
    void toolCallsFinishExecutesSkillToolAndReplaysHistoryNextRound() throws Exception {
        objects.data.put(
                new ObjectLocation("loopcraft-skills", "skills/active/v2.zip"),
                SkillPackages.zip(
                        Map.of("SKILL.md", "# 技能文档\n桩内容正文".getBytes(StandardCharsets.UTF_8))));
        responses.add(
                StubResponse.of(
                        toolCallCompletion(
                                "tool_calls", "c1", "read_skill", "{\"slug\":\"active-slug\"}")));
        responses.add(StubResponse.of(textCompletion("stop", "结合文档回答")));

        RunOutcome outcome = runOnce(defaultBudget());

        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.iterations()).isEqualTo(2);
        assertThat(outcome.toolCalls()).isEqualTo(1);
        assertThat(objects.opens).hasSize(1);
        // 第二次请求完整回放 assistant 工具调用与配对的工具结果（消息回放）。
        JsonNode messages = JSON.readTree(requests.get(1).body()).path("messages");
        assertThat(messages).hasSize(4);
        JsonNode assistant = messages.get(2);
        assertThat(assistant.path("role").asString()).isEqualTo("assistant");
        assertThat(assistant.path("tool_calls").get(0).path("id").asString()).isEqualTo("c1");
        assertThat(assistant.path("tool_calls").get(0).path("function").path("name").asString())
                .isEqualTo("read_skill");
        JsonNode toolMessage = messages.get(3);
        assertThat(toolMessage.path("role").asString()).isEqualTo("tool");
        assertThat(toolMessage.path("tool_call_id").asString()).isEqualTo("c1");
        assertThat(toolMessage.path("content").asString()).contains("桩内容正文");
    }

    @Test
    void lengthFinishWithToolCallsFailsOutputLimitWithoutExecuting() throws Exception {
        responses.add(
                StubResponse.of(
                        toolCallCompletion("length", "c1", "read_skill", "{\"slug\":\"active-slug\"}")));

        RunOutcome outcome = runOnce(defaultBudget());

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.failure()).isEqualTo(RunFailure.OUTPUT_LIMIT);
        assertThat(requests).hasSize(1);
        assertThat(objects.opens).isEmpty();
    }

    @Test
    void stopWithEmptyContentFailsAsInvalidModelResponse() {
        responses.add(StubResponse.of(textCompletion("stop", "")));

        RunOutcome outcome = runOnce(defaultBudget());

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.failure()).isEqualTo(RunFailure.INVALID_MODEL_RESPONSE);
    }

    @Test
    void runBudgetTimeoutStopsModelRequest() {
        responses.add(StubResponse.delayed(textCompletion("stop", "迟到"), 800));

        RunBudget budget = new RunBudget(12, seconds(30), millis(100), seconds(15), 32);
        RunOutcome outcome = runOnce(budget);

        assertThat(outcome.status()).isEqualTo(RunStatus.TIMED_OUT);
    }

    @Test
    void interruptionCancelsRunDuringModelCall() throws Exception {
        responses.add(StubResponse.blocking(textCompletion("stop", "被取消")));
        AtomicReference<RunOutcome> outcome = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        final Thread[] worker = new Thread[1];
        try (ExecutorService runner = Executors.newSingleThreadExecutor()) {
            Future<?> future =
                    runner.submit(
                            () -> {
                                worker[0] = Thread.currentThread();
                                outcome.set(runOnce(defaultBudget()));
                                finished.countDown();
                            });
            awaitFirstRequest();
            worker[0].interrupt();
            future.get(5, TimeUnit.SECONDS);
        }
        assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(outcome.get().status()).isEqualTo(RunStatus.CANCELLED);
    }

    private void awaitFirstRequest() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (requests.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(requests).isNotEmpty();
    }

    private RunBudget defaultBudget() {
        return new RunBudget(12, seconds(30), seconds(5), seconds(15), 32);
    }

    /** 通过真实工厂构建模型实例并执行一次完整 Agent 运行。 */
    private RunOutcome runOnce(RunBudget budget) {
        LlmCredentialCipher cipher = new LlmCredentialCipher(ENCRYPTION_KEY);
        AgentProperties properties =
                new AgentProperties(
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(15),
                        32,
                        8000,
                        1000,
                        Duration.ofMinutes(10));
        LlmModelMapper models = mock(LlmModelMapper.class);
        LlmSourceMapper sources = mock(LlmSourceMapper.class);
        when(models.selectOne(any())).thenReturn(modelRow());
        when(sources.selectOne(any())).thenReturn(sourceRow(cipher));
        ChatModelFactory factory =
                new ChatModelFactory(serving(models), serving(sources), cipher, properties);
        Map<String, ToolCallback> tools = skillTools(properties);
        ChatModelFactory.Prepared prepared = factory.prepare(1L, templateRow(), tools);
        AgentRequest request =
                new AgentRequest(
                        "请阅读技能文档",
                        "系统提示",
                        prepared.chatModel(),
                        prepared.options(),
                        tools,
                        budget);
        return new Agent(JSON).run(request, RunListener.none());
    }

    private AgentTemplate templateRow() {
        AgentTemplate template = new AgentTemplate();
        template.setId(5L);
        template.setTenantId(1L);
        template.setModelId(11L);
        template.setSystemPrompt("系统提示");
        template.setTemperature(new BigDecimal("0.7"));
        return template;
    }

    private LlmModel modelRow() {
        LlmModel model = new LlmModel();
        model.setId(11L);
        model.setTenantId(1L);
        model.setSourceId(21L);
        model.setModelId("it-stub-model");
        model.setEnabled(true);
        return model;
    }

    private LlmSource sourceRow(LlmCredentialCipher cipher) {
        LlmSource source = new LlmSource();
        source.setId(21L);
        source.setTenantId(1L);
        source.setProtocol("OPENAI_COMPATIBLE");
        source.setBaseUrl(baseUrl);
        source.setApiKeyCiphertext(cipher.encrypt(1L, "sk-stub-key"));
        source.setEnabled(true);
        return source;
    }

    /** 真实 SkillTools 工具表：模板绑定一个技能版本，文档由内存存储提供。 */
    private Map<String, ToolCallback> skillTools(AgentProperties properties) {
        TemplateSkillMapper templateSkills = mock(TemplateSkillMapper.class);
        SkillMapper skills = mock(SkillMapper.class);
        SkillVersionMapper versions = mock(SkillVersionMapper.class);
        TemplateSkill row = new TemplateSkill();
        row.setTenantId(1L);
        row.setTemplateId(5L);
        row.setSkillId(31L);
        row.setVersionNumber(2);
        row.setPosition(1);
        when(templateSkills.selectList(any())).thenReturn(List.of(row));
        Skill skill = new Skill();
        skill.setTenantId(1L);
        skill.setSlug("active-slug");
        skill.setName("技能");
        skill.setDescription("描述");
        skill.setEnabled(true);
        skill.setDeleted(false);
        when(skills.selectOne(any())).thenReturn(skill);
        SkillVersion version = new SkillVersion();
        version.setTenantId(1L);
        version.setSkillId(31L);
        version.setNumber(2);
        version.setBucket("loopcraft-skills");
        version.setObjectKey("skills/active/v2.zip");
        when(versions.selectOne(any())).thenReturn(version);
        return new SkillTools(
                        serving(templateSkills),
                        serving(skills),
                        serving(versions),
                        serving((FileStorage) objects),
                        JSON,
                        properties)
                .forTemplate(1L, 5L);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())
                    || !exchange.getRequestURI().getPath().endsWith("/chat/completions")) {
                byte[] missing = "not found".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, missing.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(missing);
                }
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(
                    new RecordedRequest(
                            exchange.getRequestHeaders().getFirst("Authorization"), body));
            StubResponse next = responses.poll();
            if (next == null) {
                throw new IllegalStateException("桩响应脚本已耗尽");
            }
            blocking.awaitIfRequested(next);
            byte[] bytes = next.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } finally {
            exchange.close();
        }
    }

    private static String textCompletion(String finish, String content) {
        return """
                {
                  "id": "chatcmpl-stub",
                  "object": "chat.completion",
                  "created": 1770000000,
                  "model": "it-stub-model",
                  "choices": [
                    {
                      "index": 0,
                      "message": {"role": "assistant", "content": %s},
                      "finish_reason": "%s"
                    }
                  ],
                  "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
                }
                """
                .formatted(escape(content), finish);
    }

    private static String toolCallCompletion(String finish, String callId, String name, String arguments) {
        return """
                {
                  "id": "chatcmpl-stub",
                  "object": "chat.completion",
                  "created": 1770000000,
                  "model": "it-stub-model",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": null,
                        "tool_calls": [
                          {
                            "id": "%s",
                            "type": "function",
                            "function": {"name": "%s", "arguments": %s}
                          }
                        ]
                      },
                      "finish_reason": "%s"
                    }
                  ],
                  "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
                }
                """
                .formatted(callId, name, escape(arguments), finish);
    }

    /** 桩响应内的 JSON 字符串值转义，避免双引号破坏外层报文。 */
    private static String escape(String value) {
        return JSON.writeValueAsString(value);
    }

    private static Duration seconds(long value) {
        return Duration.ofSeconds(value);
    }

    private static Duration millis(long value) {
        return Duration.ofMillis(value);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> serving(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(bean);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }

    private record RecordedRequest(String authorization, String body) {}

    /** 桩脚本响应：可附带固定延迟或等待取消测试释放的阻塞点。 */
    private record StubResponse(String body, long delayMillis, boolean blocking) {

        static StubResponse of(String body) {
            return new StubResponse(body, 0, false);
        }

        static StubResponse delayed(String body, long delayMillis) {
            return new StubResponse(body, delayMillis, false);
        }

        static StubResponse blocking(String body) {
            return new StubResponse(body, 0, true);
        }
    }

    /** 取消测试用的阻塞门闩；release 之后所有等待立即放行。 */
    private static final class BlockingControl {

        private volatile CountDownLatch latch = new CountDownLatch(0);

        private volatile boolean hold;

        void reset() {
            hold = false;
        }

        void awaitIfRequested(StubResponse response) {
            if (response.blocking() && !hold) {
                hold = true;
                latch = new CountDownLatch(1);
            }
            if (response.blocking()) {
                awaitQuietly(latch);
            } else if (response.delayMillis() > 0) {
                awaitQuietly(new CountDownLatch(1), response.delayMillis());
            }
        }

        void release() {
            latch.countDown();
        }

        private static void awaitQuietly(CountDownLatch latch) {
            awaitQuietly(latch, Long.MAX_VALUE);
        }

        private static void awaitQuietly(CountDownLatch latch, long millis) {
            try {
                latch.await(millis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 只支持 open 的内存对象存储，供 read_skill 读取技能文档。 */
    private static final class MemoryStorage implements FileStorage {

        private final Map<ObjectLocation, byte[]> data = new LinkedHashMap<>();

        private final List<ObjectLocation> opens = new ArrayList<>();

        @Override
        public StoredFile upload(ObjectLocation location, Path source, String filename, String contentType) {
            throw new UnsupportedOperationException("只读测试桩");
        }

        @Override
        public StoredFile stat(ObjectLocation location) {
            throw new UnsupportedOperationException("只读测试桩");
        }

        @Override
        public FileDownload open(ObjectLocation location) {
            opens.add(location);
            byte[] bytes = data.get(location);
            return new FileDownload(
                    "skill.zip", "application/zip", bytes.length, new ByteArrayInputStream(bytes));
        }

        @Override
        public void delete(ObjectLocation location) {
            throw new UnsupportedOperationException("只读测试桩");
        }

        @Override
        public URI presignDownload(ObjectLocation location) {
            throw new UnsupportedOperationException("只读测试桩");
        }
    }

}
