package io.github.chalmery.loopcraft.agent.core;

import io.github.chalmery.loopcraft.tool.ToolExecutor;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 手写 ReAct 循环：模型请求、工具执行、观察回填、再请求，全部决策集中可见。
 *
 * <p>预算与终止遵循提案第 7/9 节：单调时钟维护剩余时间，模型与工具调用前都检查；
 * 响应判定顺序为先排除异常与截断，再看工具块；可预期工具错误回填观察结果交给模型修正，
 * 不可预期异常、超时与取消终止整次运行。messages、预算与计数都是本次运行的局部数据。
 */
@Component
public class Agent {

    private final ObjectMapper json;

    public Agent(ObjectMapper json) {
        this.json = json;
    }

    public RunOutcome run(AgentRequest request, RunListener listener) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                return loop(request, listener, executor);
            } catch (AbortRun abort) {
                return new RunOutcome(
                        abort.status, abort.failure, null, abort.iterations, abort.toolCalls);
            }
        }
    }

    private RunOutcome loop(AgentRequest request, RunListener listener, ExecutorService executor) {
        RunBudget budget = request.budget();
        long deadline = System.nanoTime() + budget.runTimeout().toNanos();
        List<Message> messages = initialMessages(request);
        ToolExecutor tools = new ToolExecutor(request.tools(), json);
        Set<String> usedCallIds = new HashSet<>();
        int toolCallsUsed = 0;
        int iteration = 0;

        while (true) {
            if (iteration >= budget.maxIterations()) {
                return RunOutcome.iterationLimit(iteration, toolCallsUsed);
            }
            requireRemaining(deadline, iteration, toolCallsUsed);
            iteration++;
            listener.iterationStarted(iteration);
            ChatResponse response =
                    callModel(executor, request, messages, deadline, iteration, toolCallsUsed);
            Decision decision = decide(response);
            if (decision.kind() == Decision.Kind.COMPLETED) {
                return RunOutcome.completed(decision.text(), iteration, toolCallsUsed);
            }
            if (decision.kind() == Decision.Kind.FAILED) {
                return RunOutcome.failed(decision.failure(), iteration, toolCallsUsed);
            }
            if (iteration >= budget.maxIterations()) {
                // 最后一轮仍要求工具：已无下一次观察预算，整批不执行（提案 D5 硬停）。
                return RunOutcome.iterationLimit(iteration, toolCallsUsed);
            }
            if (toolCallsUsed + decision.calls().size() > budget.maxToolCalls()) {
                // 整批会超限则一个也不执行，不挑部分执行；失败调用同样消耗额度。
                return RunOutcome.toolCallLimit(iteration, toolCallsUsed);
            }
            if (!validCallIds(decision.calls(), usedCallIds)) {
                return RunOutcome.failed(RunFailure.INVALID_MODEL_RESPONSE, iteration, toolCallsUsed);
            }
            // 保留模型返回的完整 assistant 消息（含 tool calls 与 metadata），
            // 工具结果按相同 call id 配对回填，缺一不可进入下一轮。
            messages.add(decision.assistant());
            usedCallIds.addAll(callIds(decision.calls()));
            messages.add(
                    executeTools(
                            executor, tools, decision.calls(), deadline, budget, listener,
                            iteration, toolCallsUsed));
            toolCallsUsed += decision.calls().size();
        }
    }

    private List<Message> initialMessages(AgentRequest request) {
        List<Message> messages = new ArrayList<>();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(new SystemMessage(request.systemPrompt()));
        }
        messages.add(new UserMessage(request.message()));
        return messages;
    }

    /** 单次模型调用；请求超时取全局上限与 run 剩余时间的较小值。 */
    private ChatResponse callModel(
            ExecutorService executor,
            AgentRequest request,
            List<Message> messages,
            long deadline,
            int iteration,
            int toolCalls) {
        long remainingNanos = deadline - System.nanoTime();
        Duration timeout = shorter(request.budget().modelRequestTimeout(), remainingNanos);
        Future<ChatResponse> future =
                executor.submit(
                        () ->
                                request.chatModel()
                                        .call(new Prompt(new ArrayList<>(messages), request.options())));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            throw new AbortRun(RunStatus.TIMED_OUT, null, iteration, toolCalls);
        } catch (ExecutionException error) {
            throw new AbortRun(RunStatus.FAILED, RunFailure.MODEL_ERROR, iteration, toolCalls);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AbortRun(RunStatus.CANCELLED, null, iteration, toolCalls);
        }
    }

    /** 响应判定（提案 7.3 顺序）：先排除异常与截断，再看工具块，最后才是文本完成。 */
    private Decision decide(ChatResponse response) {
        if (response == null
                || response.getResults() == null
                || response.getResults().isEmpty()
                || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return Decision.failed(RunFailure.INVALID_MODEL_RESPONSE);
        }
        AssistantMessage output = response.getResult().getOutput();
        String finish = finishReason(response);
        boolean hasCalls = output.hasToolCalls();
        if ("LENGTH".equalsIgnoreCase(finish)) {
            // 截断的工具调用参数可能不完整，即使存在也不执行。
            return Decision.failed(RunFailure.OUTPUT_LIMIT);
        }
        if (!finishNormal(finish)) {
            return Decision.failed(RunFailure.INVALID_MODEL_RESPONSE);
        }
        if (hasCalls) {
            return Decision.tools(output, output.getToolCalls());
        }
        if ("TOOL_EXECUTION".equalsIgnoreCase(finish) || "TOOL_CALLS".equalsIgnoreCase(finish)) {
            // 声称工具调用结束却没有任何调用，视为协议异常，不猜测修复。
            return Decision.failed(RunFailure.INVALID_MODEL_RESPONSE);
        }
        String text = output.getText();
        if (text == null || text.isBlank()) {
            return Decision.failed(RunFailure.INVALID_MODEL_RESPONSE);
        }
        return Decision.completed(text.strip());
    }

    private String finishReason(ChatResponse response) {
        if (response.getResult().getMetadata() == null) {
            return null;
        }
        return response.getResult().getMetadata().getFinishReason();
    }

    /** 适配器正常结束类型：stop、空，以及工具块驱动的 tool execution / tool calls。 */
    private boolean finishNormal(String finish) {
        return finish == null
                || "STOP".equalsIgnoreCase(finish)
                || "NULL".equalsIgnoreCase(finish)
                || "TOOL_EXECUTION".equalsIgnoreCase(finish)
                || "TOOL_CALLS".equalsIgnoreCase(finish);
    }

    /** 批内串行执行；每个工具调用前检查剩余时间，超时后停止派发，已完成的保留。 */
    private ToolResponseMessage executeTools(
            ExecutorService executor,
            ToolExecutor tools,
            List<AssistantMessage.ToolCall> calls,
            long deadline,
            RunBudget budget,
            RunListener listener,
            int iteration,
            int toolCallsBefore) {
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        int executed = 0;
        for (AssistantMessage.ToolCall call : calls) {
            requireRemaining(deadline, iteration, toolCallsBefore + executed);
            listener.toolCalled(call.id(), call.name());
            long startedAt = System.nanoTime();
            String result =
                    executeOne(
                            executor, tools, call, deadline, budget, iteration,
                            toolCallsBefore + executed);
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            listener.toolFinished(
                    call.id(), call.name(), tools.ok(result), tools.truncated(result), durationMs);
            responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), result));
            executed++;
        }
        return ToolResponseMessage.builder().responses(responses).build();
    }

    /** 白名单外的工具不执行，转为错误观察；已注册工具按剩余时间受控执行。 */
    private String executeOne(
            ExecutorService executor,
            ToolExecutor tools,
            AssistantMessage.ToolCall call,
            long deadline,
            RunBudget budget,
            int iteration,
            int toolCalls) {
        if (!tools.registered(call.name())) {
            return tools.unknownTool(call.name());
        }
        long remainingNanos = deadline - System.nanoTime();
        Duration timeout = shorter(budget.toolTimeout(), remainingNanos);
        Future<String> future = executor.submit(() -> tools.execute(call));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            throw new AbortRun(RunStatus.TIMED_OUT, null, iteration, toolCalls);
        } catch (ExecutionException error) {
            // 不可预期异常执行状态不明，终止整次运行，不伪装成工具错误结果。
            throw new AbortRun(RunStatus.FAILED, RunFailure.TOOL_EXECUTION_UNCERTAIN, iteration, toolCalls);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AbortRun(RunStatus.CANCELLED, null, iteration, toolCalls);
        }
    }

    private void requireRemaining(long deadline, int iteration, int toolCalls) {
        if (deadline - System.nanoTime() <= 0) {
            throw new AbortRun(RunStatus.TIMED_OUT, null, iteration, toolCalls);
        }
    }

    /** call id 非空、批内不重复且本 run 内未出现过；违反视为协议异常，整批不执行。 */
    private boolean validCallIds(List<AssistantMessage.ToolCall> calls, Set<String> usedCallIds) {
        Set<String> batch = new HashSet<>();
        for (AssistantMessage.ToolCall call : calls) {
            if (call.id() == null || call.id().isBlank()) {
                return false;
            }
            if (!batch.add(call.id()) || usedCallIds.contains(call.id())) {
                return false;
            }
        }
        return true;
    }

    private Set<String> callIds(List<AssistantMessage.ToolCall> calls) {
        Set<String> ids = new HashSet<>();
        for (AssistantMessage.ToolCall call : calls) {
            ids.add(call.id());
        }
        return ids;
    }

    private Duration shorter(Duration configured, long remainingNanos) {
        Duration remaining = Duration.ofNanos(Math.max(remainingNanos, 1));
        return configured.compareTo(remaining) <= 0 ? configured : remaining;
    }

    /** 运行内部终止信号；携带终止时的计数，避免状态散落在多个返回路径上。 */
    private static final class AbortRun extends RuntimeException {
        private final RunStatus status;
        private final RunFailure failure;
        private final int iterations;
        private final int toolCalls;

        AbortRun(RunStatus status, RunFailure failure, int iterations, int toolCalls) {
            super(status.name(), null, false, false);
            this.status = status;
            this.failure = failure;
            this.iterations = iterations;
            this.toolCalls = toolCalls;
        }
    }

    /** 一次模型响应的判定结果：完成、失败或进入工具批次。 */
    private record Decision(Kind kind, RunFailure failure, String text, AssistantMessage assistant,
            List<AssistantMessage.ToolCall> calls) {

        enum Kind {
            COMPLETED, FAILED, TOOLS
        }

        static Decision completed(String text) {
            return new Decision(Kind.COMPLETED, null, text, null, null);
        }

        static Decision failed(RunFailure failure) {
            return new Decision(Kind.FAILED, failure, null, null, null);
        }

        static Decision tools(AssistantMessage assistant, List<AssistantMessage.ToolCall> calls) {
            return new Decision(Kind.TOOLS, null, null, assistant, List.copyOf(calls));
        }
    }
}
