package io.github.chalmery.loopcraft.agent.controller;

import io.github.chalmery.loopcraft.agent.dto.AgentRequests;
import io.github.chalmery.loopcraft.agent.dto.AgentViews;
import io.github.chalmery.loopcraft.agent.service.AgentChatService;
import io.github.chalmery.loopcraft.identity.service.IdentityService;
import io.github.chalmery.loopcraft.shared.ApiResponse;
import io.github.chalmery.loopcraft.shared.TraceIdContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Agent 运行入口：chat 受理返回 runId，SSE 订阅按事件序号重放与实时推送。 */
@RestController
@RequestMapping("/api/tenants/{tenantId}/agent")
public class AgentController {

    private final IdentityService identity;

    private final AgentChatService agentChat;

    public AgentController(IdentityService identity, AgentChatService agentChat) {
        this.identity = identity;
        this.agentChat = agentChat;
    }

    /** 所有租户成员可发起；模型、提示词与预算由模板决定（D14），事件经双层通道推送。 */
    @PostMapping("/chat")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AgentViews.RunStarted> chat(
            Authentication auth,
            @PathVariable @Positive long tenantId,
            @Valid @RequestBody AgentRequests.Chat request) {
        return new ApiResponse<>(
                HttpStatus.ACCEPTED.value(),
                "已受理",
                agentChat.start(
                        identity.current(auth), tenantId, request.templateId(), request.message()),
                TraceIdContext.current());
    }

    /** 订阅运行事件；EventSource 断线自动重连并携带 Last-Event-ID 从断点续传。 */
    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            Authentication auth,
            @PathVariable @Positive long tenantId,
            @PathVariable @Size(max = 36) String runId,
            @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId) {
        return agentChat.subscribe(identity.current(auth), tenantId, runId, lastEventId);
    }
}
