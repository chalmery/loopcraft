package io.github.chalmery.loopcraft.agent.service;

import io.github.chalmery.loopcraft.agent.model.AgentRequest;
import io.github.chalmery.loopcraft.agent.model.RunBudget;
import io.github.chalmery.loopcraft.agent.model.RunFailure;
import io.github.chalmery.loopcraft.agent.model.RunListener;
import io.github.chalmery.loopcraft.agent.model.RunOutcome;
import io.github.chalmery.loopcraft.agent.model.RunStatus;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** 假模型确定性单元测试，覆盖提案第 11 节的循环、判定、预算与终止用例。 */
class AgentTests {

    private final Agent agent = new Agent(new ObjectMapper());

    @Test
    void completesDirectTextAnswerWithoutTools() {
        ScriptedChatModel model = ScriptedChatModel.of(text("最终答案"));
        RecordingListener listener = new RecordingListener();

        RunOutcome outcome = agent.run(request(model, tools(), budget(12)), listener);

        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.answer()).isEqualTo("最终答案");
        assertThat(outcome.iterations()).isEqualTo(1);
        assertThat(outcome.toolCalls()).isZero();
        assertThat(listener.events).containsExactly("iteration:1");
        List<Message> first = model.prompts.get(0).getInstructions();
        assertThat(first).hasSize(2);
        assertThat(first.get(0).getMessageType()).isEqualTo(MessageType.SYSTEM);
        assertThat(first.get(1).getMessageType()).isEqualTo(MessageType.USER);
    }

    @Test
    void executesSingleToolRoundAndKeepsPairedMessages() {
        FakeTool echo = FakeTool.of("echo", arguments -> "{\"ok\":true,\"data\":\"回声\"}");
        ScriptedChatModel model =
                ScriptedChatModel.of(
                        toolTurn("TOOL_EXECUTION", call("c1", "echo", "{\"text\":\"hi\"}")),
                        text("结合工具结果回答"));

        RunOutcome outcome = agent.run(request(model, tools(echo), budget(12)), RunListener.none());

        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.iterations()).isEqualTo(2);
        assertThat(outcome.toolCalls()).isEqualTo(1);
        assertThat(echo.inputs).containsExactly("{\"text\":\"hi\"}");
        List<Message> second = model.prompts.get(1).getInstructions();
        assertThat(second).hasSize(4);
        assertThat(second.get(2)).isInstanceOf(AssistantMessage.class);
        assertThat(((AssistantMessage) second.get(2)).getToolCalls())
                .extracting(AssistantMessage.ToolCall::id)
                .containsExactly("c1");
        assertThat(second.get(3)).isInstanceOf(ToolResponseMessage.class);
        assertThat(((ToolResponseMessage) second.get(3)).getResponses())
                .extracting(ToolResponseMessage.ToolResponse::id)
                .containsExactly("c1");
        assertThat(((ToolResponseMessage) second.get(3)).getResponses().get(0).responseData())
                .contains("\"data\":\"回声\"");
    }

    @Test
    void supportsConsecutiveToolRounds() {
        FakeTool echo = FakeTool.of("echo", arguments -> "{\"ok\":true}");
        ScriptedChatModel model =
                ScriptedChatModel.of(
                        toolTurn("TOOL_EXECUTION", call("c1", "echo", "{}")),
                        toolTurn("STOP", call("c2", "echo", "{}")),
                        text("完成"));

        RunOutcome outcome = agent.run(request(model, tools(echo), budget(12)), RunListener.none());

        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.iterations()).isEqualTo(3);
        assertThat(outcome.toolCalls()).isEqualTo(2);
        List<Message> third = model.prompts.get(2).getInstructions();
        assertThat(third).hasSize(6);
    }

    @Test
    void executesSameBatchToolsInModelOrder() {
        List<String> order = new ArrayList<>();
        FakeTool first = FakeTool.of("first", arguments -> {
            order.add("first");
            return "{\"ok\":true}";
        });
        FakeTool second = FakeTool.of("second", arguments -> {
            order.add("second");
            return "{\"ok\":true}";
        });
        ScriptedChatModel model =
                ScriptedChatModel.of(
                        toolTurn("TOOL_EXECUTION",
                                call("c1", "first", "{}"),
                                call("c2", "second", "{}")),
                        text("完成"));

        RunOutcome outcome = agent.run(request(model, tools(first, second), budget(12)), RunListener.none());

        assertThat(outcome.completed()).isTrue();
        assertThat(order).containsExactly("first", "second");
        List<Message> secondPrompt = model.prompts.get(1).getInstructions();
        ToolResponseMessage responses = (ToolResponseMessage) secondPrompt.get(3);
        assertThat(responses.getResponses())
                .extracting(ToolResponseMessage.ToolResponse::id)
                .containsExactly("c1", "c2");
    }

    @Test
    void lengthFinishSkipsToolExecution() {
        FakeTool echo = FakeTool.of("echo", arguments -> "{\"ok\":true}");
        ScriptedChatModel model =
                ScriptedChatModel.of(
                        toolTurn("LENGTH", call("c1", "echo", "{\"a\":1}")));

        RunOutcome outcome = agent.run(request(model, tools(echo), budget(12)), RunListener.none());

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.failure()).isEqualTo(RunFailure.OUTPUT_LIMIT);
        assertThat(echo.inputs).isEmpty();
    }

    @Test
    void unknownToolFeedsErrorObservationWithoutAborting() {
        ScriptedChatModel model =
                ScriptedChatModel.of(
                        toolTurn("TOOL_EXECUTION", call("c1", "missing_tool", "{}")),
                        text("忽略不可用工具后回答"));

        RunOutcome outcome = agent.run(request(model, tools(), budget(12)), RunListener.none());

        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.toolCalls()).isEqualTo(1);
        ToolResponseMessage responses =
                (ToolResponseMessage) model.prompts.get(1).getInstructions().get(3);
        assertThat(responses.getResponses().get(0).responseData()).contains("UNKNOWN_TOOL");
    }

    @Test
    void blankCallIdRejectsWholeBatch() {
        FakeTool echo = FakeTool.of("echo", arguments -> "{\"ok\":true}");
        ScriptedChatModel model =
                ScriptedChatModel.of(toolTurn("TOOL_EXECUTION", call("", "echo", "{}")));

        RunOutcome outcome = agent.run(request(model, tools(echo), budget(12)), RunListener.none());

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.failure()).isEqualTo(RunFailure.INVALID_MODEL_RESPONSE);
        assertThat(echo.inputs).isEmpty();
    }

    @Test
    void duplicateCallIdWithinBatchRejectsWholeBatch() {
        FakeTool echo = FakeTool.of("echo", arguments -> "{\"ok\":true}");
        ScriptedChatModel model =
                ScriptedChatModel.of(
                        toolTurn("TOOL_EXECUTION",
                                call("c1", "echo", "{}"),
                                call("c1", "echo", "{}")));

        RunOutcome outcome = agent.run(request(model, tools(echo), budget(12)), RunListener.none());

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.failure()).isEqualTo(RunFailure.INVALID_MODEL_RESPONSE);
        assertThat(echo.inputs).isEmpty();
    }

    @Test
    void reusedCallIdAcrossIterationsFailsRun() {
        FakeTool echo = FakeTool.of("echo", arguments -> "{\"ok\":true}");
        ScriptedChatModel model =
                ScriptedChatModel.of(
                        toolTurn("TOOL_EXECUTION", call("c1", "echo", "{}")),
                        toolTurn("TOOL_EXECUTION", call("c1", "echo", "{}")));

        RunOutcome outcome = agent.run(request(model, tools(echo), budget(12)), RunListener.none());

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.failure()).isEqualTo(RunFailure.INVALID_MODEL_RESPONSE);
        assertThat(echo.inputs).hasSize(1);
    }

    @Test
    void singleIterationBudgetAllowsDirectText() {
        ScriptedChatModel model = ScriptedChatModel.of(text("直接回答"));

        RunOutcome outcome = agent.run(request(model, tools(), budget(1)), RunListener.none());

        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.iterations()).isEqualTo(1);
    }

    @Test
    void singleIterationBudgetStopsToolRequestWithoutExecution() {
        FakeTool echo = FakeTool.of("echo", arguments -> "{\"ok\":true}");
        ScriptedChatModel model =
                ScriptedChatModel.of(toolTurn("TOOL_EXECUTION", call("c1", "echo", "{}")));

        RunOutcome outcome = agent.run(request(model, tools(echo), budget(1)), RunListener.none());

        assertThat(outcome.status()).isEqualTo(RunStatus.ITERATION_LIMIT);
        assertThat(outcome.iterations()).isEqualTo(1);
        assertThat(echo.inputs).isEmpty();
    }

    @Test
    void lastIterationToolRequestHardStops() {
        FakeTool echo = FakeTool.of("echo", arguments -> "{\"ok\":true}");
        ScriptedChatModel model =
                ScriptedChatModel.of(
                        toolTurn("TOOL_EXECUTION", call("c1", "echo", "{}")),
                        toolTurn("TOOL_EXECUTION", call("c2", "echo", "{}")));

        RunOutcome outcome = agent.run(request(model, tools(echo), budget(2)), RunListener.none());

        assertThat(outcome.status()).isEqualTo(RunStatus.ITERATION_LIMIT);
        assertThat(outcome.iterations()).isEqualTo(2);
        assertThat(outcome.toolCalls()).isEqualTo(1);
        assertThat(echo.inputs).hasSize(1);
    }

    @Test
    void wholeBatchSkippedWhenToolCallLimitWouldBeExceeded() {
        FakeTool echo = FakeTool.of("echo", arguments -> "{\"ok\":true}");
        ScriptedChatModel model =
                ScriptedChatModel.of(
                        toolTurn("TOOL_EXECUTION",
                                call("c1", "echo", "{}"),
                                call("c2", "echo", "{}")));

        RunBudget budget = new RunBudget(12, seconds(30), seconds(5), seconds(2), 1);
        RunOutcome outcome = agent.run(request(model, tools(echo), budget), RunListener.none());

        assertThat(outcome.status()).isEqualTo(RunStatus.TOOL_CALL_LIMIT);
        assertThat(outcome.toolCalls()).isZero();
        assertThat(echo.inputs).isEmpty();
    }

    @Test
    void toolCallLimitCountsFailedAndExecutedCalls() {
        FakeTool echo = FakeTool.of("echo", arguments -> "{\"ok\":true}");
        ScriptedChatModel model =
                ScriptedChatModel.of(
                        toolTurn("TOOL_EXECUTION", call("c1", "echo", "{}")),
                        toolTurn("TOOL_EXECUTION", call("c2", "echo", "{}")));

        RunBudget budget = new RunBudget(12, seconds(30), seconds(5), seconds(2), 1);
        RunOutcome outcome = agent.run(request(model, tools(echo), budget), RunListener.none());

        assertThat(outcome.status()).isEqualTo(RunStatus.TOOL_CALL_LIMIT);
        assertThat(outcome.toolCalls()).isEqualTo(1);
        assertThat(echo.inputs).hasSize(1);
    }

    @Test
    void modelFailureFailsRunWithModelError() {
        ScriptedChatModel model = ScriptedChatModel.errorAt(0);

        RunOutcome outcome = agent.run(request(model, tools(), budget(12)), RunListener.none());

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.failure()).isEqualTo(RunFailure.MODEL_ERROR);
    }

    @Test
    void emptyResponseFailsAsInvalidModelResponse() {
        ScriptedChatModel model = ScriptedChatModel.of(new ChatResponse(List.of()));

        RunOutcome outcome = agent.run(request(model, tools(), budget(12)), RunListener.none());

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.failure()).isEqualTo(RunFailure.INVALID_MODEL_RESPONSE);
    }

    @Test
    void blankTextWithoutToolCallsFailsAsInvalidModelResponse() {
        ScriptedChatModel model = ScriptedChatModel.of(text("   "));

        RunOutcome outcome = agent.run(request(model, tools(), budget(12)), RunListener.none());

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.failure()).isEqualTo(RunFailure.INVALID_MODEL_RESPONSE);
    }

    @Test
    void toolTimeoutStopsRunAndFurtherDispatch() {
        FakeTool slow = FakeTool.of("slow", arguments -> {
            sleepQuietly(500);
            return "{\"ok\":true}";
        });
        FakeTool never = FakeTool.of("never", arguments -> "{\"ok\":true}");
        ScriptedChatModel model =
                ScriptedChatModel.of(
                        toolTurn("TOOL_EXECUTION",
                                call("c1", "slow", "{}"),
                                call("c2", "never", "{}")),
                        text("不会到达"));

        RunBudget budget = new RunBudget(12, seconds(30), seconds(5), millis(50), 32);
        RunOutcome outcome = agent.run(request(model, tools(slow, never), budget), RunListener.none());

        assertThat(outcome.status()).isEqualTo(RunStatus.TIMED_OUT);
        assertThat(never.inputs).isEmpty();
    }

    @Test
    void modelRequestTimeoutStopsRun() {
        ScriptedChatModel model = new ScriptedChatModel(
                List.of(prompt -> {
                    sleepQuietly(300);
                    return text("迟到");
                }));

        RunBudget budget = new RunBudget(12, seconds(30), millis(50), seconds(2), 32);
        RunOutcome outcome = agent.run(request(model, tools(), budget), RunListener.none());

        assertThat(outcome.status()).isEqualTo(RunStatus.TIMED_OUT);
    }

    @Test
    void runTimeoutConstrainsRemainingRequestBudget() {
        ScriptedChatModel model = new ScriptedChatModel(
                List.of(prompt -> {
                    sleepQuietly(300);
                    return text("迟到");
                }));

        // 模型自身超时足够长，但 run 剩余时间更小，单次请求必须被剩余预算约束。
        RunBudget budget = new RunBudget(12, millis(80), seconds(5), seconds(2), 32);
        RunOutcome outcome = agent.run(request(model, tools(), budget), RunListener.none());

        assertThat(outcome.status()).isEqualTo(RunStatus.TIMED_OUT);
    }

    @Test
    void interruptionCancelsRun() throws Exception {
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch modelRelease = new CountDownLatch(1);
        ScriptedChatModel model = new ScriptedChatModel(
                List.of(prompt -> {
                    modelStarted.countDown();
                    try {
                        modelRelease.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                    return text("取消后");
                }));

        final Thread[] worker = new Thread[1];
        try (ExecutorService runner = Executors.newSingleThreadExecutor()) {
            Future<RunOutcome> future =
                    runner.submit(() -> {
                        worker[0] = Thread.currentThread();
                        return agent.run(request(model, tools(), budget(12)), RunListener.none());
                    });
            assertThat(modelStarted.await(2, TimeUnit.SECONDS)).isTrue();
            worker[0].interrupt();
            modelRelease.countDown();
            RunOutcome outcome = future.get(5, TimeUnit.SECONDS);
            assertThat(outcome.status()).isEqualTo(RunStatus.CANCELLED);
        }
    }

    @Test
    void unexpectedToolExceptionFailsRunWithUncertainExecution() {
        FakeTool broken = FakeTool.of("broken", arguments -> {
            throw new IllegalStateException("工具内部错误");
        });
        ScriptedChatModel model =
                ScriptedChatModel.of(toolTurn("TOOL_EXECUTION", call("c1", "broken", "{}")));

        RunOutcome outcome = agent.run(request(model, tools(broken), budget(12)), RunListener.none());

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.failure()).isEqualTo(RunFailure.TOOL_EXECUTION_UNCERTAIN);
    }

    @Test
    void concurrentRunsKeepIsolatedHistoriesAndBudgets() throws Exception {
        ScriptedChatModel firstModel =
                ScriptedChatModel.of(
                        toolTurn("TOOL_EXECUTION", call("a1", "echo", "{}")),
                        text("第一个完成"));
        ScriptedChatModel secondModel =
                ScriptedChatModel.of(
                        toolTurn("TOOL_EXECUTION", call("b1", "echo", "{}")),
                        toolTurn("TOOL_EXECUTION", call("b2", "echo", "{}")),
                        text("第二个完成"));
        FakeTool echo = FakeTool.of("echo", arguments -> "{\"ok\":true}");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<RunOutcome> outcomes = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<RunOutcome>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(2, TimeUnit.SECONDS)).isTrue();
                return agent.run(request(firstModel, tools(echo), budget(12)), RunListener.none());
            }));
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(2, TimeUnit.SECONDS)).isTrue();
                return agent.run(request(secondModel, tools(echo), budget(12)), RunListener.none());
            }));
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<RunOutcome> future : futures) {
                outcomes.add(future.get(5, TimeUnit.SECONDS));
            }
        }

        assertThat(outcomes).extracting(RunOutcome::answer).containsExactlyInAnyOrder("第一个完成", "第二个完成");
        assertThat(firstModel.prompts).hasSize(2);
        assertThat(secondModel.prompts).hasSize(3);
    }

    @Test
    void listenerReportsEnvelopeOutcomeWithoutContent() {
        FakeTool echo = FakeTool.of("echo", arguments -> "{\"ok\":true,\"truncated\":true,\"data\":\"x\"}");
        ScriptedChatModel model =
                ScriptedChatModel.of(
                        toolTurn("TOOL_EXECUTION", call("c1", "echo", "{}")),
                        text("完成"));
        RecordingListener listener = new RecordingListener();

        agent.run(request(model, tools(echo), budget(12)), listener);

        assertThat(listener.events)
                .containsExactly("iteration:1", "tool:c1", "finished:c1:true:true", "iteration:2");
    }

    private AgentRequest request(ChatModel model, Map<String, ToolCallback> tools, RunBudget budget) {
        return new AgentRequest(
                "请处理",
                "你是测试助手",
                model,
                OpenAiChatOptions.builder().toolCallbacks(List.copyOf(tools.values())).build(),
                tools,
                budget);
    }

    private Map<String, ToolCallback> tools(FakeTool... defined) {
        Map<String, ToolCallback> map = new LinkedHashMap<>();
        for (FakeTool tool : defined) {
            map.put(tool.name, tool);
        }
        return Collections.unmodifiableMap(map);
    }

    private RunBudget budget(int maxIterations) {
        return new RunBudget(maxIterations, seconds(30), seconds(5), seconds(2), 32);
    }

    private static Duration seconds(long value) {
        return Duration.ofSeconds(value);
    }

    private static Duration millis(long value) {
        return Duration.ofMillis(value);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static ChatResponse text(String content) {
        AssistantMessage assistant = AssistantMessage.builder().content(content).build();
        return new ChatResponse(
                List.of(new Generation(assistant, metadata("STOP"))));
    }

    private static ChatResponse toolTurn(String finish, AssistantMessage.ToolCall... calls) {
        AssistantMessage assistant =
                AssistantMessage.builder().content("").toolCalls(List.of(calls)).build();
        return new ChatResponse(List.of(new Generation(assistant, metadata(finish))));
    }

    private static AssistantMessage.ToolCall call(String id, String name, String arguments) {
        return new AssistantMessage.ToolCall(id, "function", name, arguments);
    }

    private static ChatGenerationMetadata metadata(String finishReason) {
        return ChatGenerationMetadata.builder().finishReason(finishReason).build();
    }

    /** 按序弹出预设响应并记录每次收到 Prompt 的假模型；脚本耗尽或指定步抛错模拟模型异常。 */
    private static final class ScriptedChatModel implements ChatModel {
        private final Queue<Function<Prompt, ChatResponse>> steps;
        private final List<Prompt> prompts = new ArrayList<>();

        private ScriptedChatModel(List<Function<Prompt, ChatResponse>> steps) {
            this.steps = new ConcurrentLinkedQueue<>(steps);
        }

        static ScriptedChatModel of(ChatResponse... responses) {
            List<Function<Prompt, ChatResponse>> list = new ArrayList<>();
            for (ChatResponse response : responses) {
                list.add(prompt -> response);
            }
            return new ScriptedChatModel(list);
        }

        static ScriptedChatModel errorAt(int step) {
            List<Function<Prompt, ChatResponse>> list = new ArrayList<>();
            for (int i = 0; i <= step; i++) {
                list.add(prompt -> {
                    throw new IllegalStateException("模型服务错误");
                });
            }
            return new ScriptedChatModel(list);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            Function<Prompt, ChatResponse> next = steps.poll();
            if (next == null) {
                throw new IllegalStateException("测试脚本已耗尽");
            }
            return next.apply(prompt);
        }
    }

    /** 记录参数并返回预设结果的假工具；结果由行为函数决定，可模拟错误回填或异常。 */
    private static final class FakeTool implements ToolCallback {
        private final String name;
        private final Function<String, String> behavior;
        private final List<String> inputs = new ArrayList<>();

        private FakeTool(String name, Function<String, String> behavior) {
            this.name = name;
            this.behavior = behavior;
        }

        static FakeTool of(String name, Function<String, String> behavior) {
            return new FakeTool(name, behavior);
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(name)
                    .description("测试工具")
                    .inputSchema("{\"type\":\"object\"}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            inputs.add(toolInput);
            return behavior.apply(toolInput);
        }
    }

    /** 只记录事件标识，不保存任何内容。 */
    private static final class RecordingListener implements RunListener {
        private final List<String> events = new ArrayList<>();

        @Override
        public void iterationStarted(int iteration) {
            events.add("iteration:" + iteration);
        }

        @Override
        public void toolCalled(String callId, String name) {
            events.add("tool:" + callId);
        }

        @Override
        public void toolFinished(String callId, String name, boolean ok, boolean truncated, long durationMs) {
            events.add("finished:" + callId + ":" + ok + ":" + truncated);
        }
    }
}
