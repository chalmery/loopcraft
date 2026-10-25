package io.github.chalmery.loopcraft.agent.controller;

import io.github.chalmery.loopcraft.agent.dto.AgentRequests;
import io.github.chalmery.loopcraft.agent.dto.AgentViews;
import io.github.chalmery.loopcraft.agent.service.AgentChatService;
import io.github.chalmery.loopcraft.identity.service.IdentityService;
import io.github.chalmery.loopcraft.shared.ApiResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Agent 运行入口；同步阻塞至终态，未完成的运行按 D11 映射为明确失败。 */
@RestController
@RequestMapping("/api/tenants/{tenantId}/agent")
public class AgentController {

    private final IdentityService identity;

    private final AgentChatService agentChat;

    public AgentController(IdentityService identity, AgentChatService agentChat) {
        this.identity = identity;
        this.agentChat = agentChat;
    }

    /** 所有租户成员可发起；模型、提示词与预算由模板决定（D14）。 */
    @PostMapping("/chat")
    public ApiResponse<AgentViews.ChatReply> chat(
            Authentication auth,
            @PathVariable @Positive long tenantId,
            @Valid @RequestBody AgentRequests.Chat request) {
        return ApiResponse.success(
                agentChat.chat(
                        identity.current(auth), tenantId, request.templateId(), request.message()));
    }
}
