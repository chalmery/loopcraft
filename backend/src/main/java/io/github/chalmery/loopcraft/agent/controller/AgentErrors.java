package io.github.chalmery.loopcraft.agent.controller;

import io.github.chalmery.loopcraft.agent.model.RunOutcome;
import io.github.chalmery.loopcraft.agent.service.AgentRunException;
import io.github.chalmery.loopcraft.shared.ApiResponse;
import io.github.chalmery.loopcraft.shared.TraceIdContext;

import java.util.Map;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 运行终态到 HTTP 的映射（D11）：预算耗尽 422，超时 504，模型/协议错误 502。 */
@Order(0)
@RestControllerAdvice(assignableTypes = AgentController.class)
public class AgentErrors {

    @ExceptionHandler(AgentRunException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> run(AgentRunException cause) {
        RunOutcome outcome = cause.outcome();
        HttpStatus status = statusOf(outcome);
        return ResponseEntity.status(status)
                .body(
                        new ApiResponse<>(
                                status.value(),
                                message(outcome),
                                Map.of(
                                        "status",
                                        outcome.status().name(),
                                        "reason",
                                        outcome.failure() == null ? "" : outcome.failure().name()),
                                TraceIdContext.current()));
    }

    private HttpStatus statusOf(RunOutcome outcome) {
        return switch (outcome.status()) {
            case ITERATION_LIMIT, TOOL_CALL_LIMIT -> HttpStatus.UNPROCESSABLE_ENTITY;
            case TIMED_OUT -> HttpStatus.GATEWAY_TIMEOUT;
            case CANCELLED -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_GATEWAY;
        };
    }

    private String message(RunOutcome outcome) {
        return switch (outcome.status()) {
            case ITERATION_LIMIT -> "运行达到迭代上限，未完成";
            case TOOL_CALL_LIMIT -> "运行达到工具调用上限，未完成";
            case TIMED_OUT -> "运行超时，未完成";
            case CANCELLED -> "运行已取消";
            default -> "模型或工具执行失败，运行未完成";
        };
    }
}
