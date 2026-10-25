package io.github.chalmery.loopcraft.mcp.controller;

import io.github.chalmery.loopcraft.identity.service.IdentityService;
import io.github.chalmery.loopcraft.mcp.dto.McpRequests;
import io.github.chalmery.loopcraft.mcp.dto.McpViews;
import io.github.chalmery.loopcraft.mcp.service.McpServerService;
import io.github.chalmery.loopcraft.shared.ApiResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 租户内 MCP 服务配置，成员只读，OWNER 和平台管理员可管理。 */
@RestController
@RequestMapping("/api/tenants/{tenantId}/mcp-servers")
public class McpServerController {

    private final IdentityService identity;

    private final McpServerService servers;

    public McpServerController(IdentityService identity, McpServerService servers) {
        this.identity = identity;
        this.servers = servers;
    }

    /** 分页查询当前租户 MCP 服务。 */
    @GetMapping
    public ApiResponse<McpViews.Page<McpViews.Server>> list(Authentication auth,
            @PathVariable @Positive long tenantId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 160) String q) {
        return ApiResponse.success(
                servers.servers(identity.current(auth), tenantId, page, size, q));
    }

    /** 渲染表单所需的选项，如 stdio 总开关状态。 */
    @GetMapping("/options")
    public ApiResponse<McpViews.Options> options(Authentication auth,
            @PathVariable @Positive long tenantId) {
        return ApiResponse.success(servers.options(identity.current(auth), tenantId));
    }

    /** 管理员创建 MCP 服务；stdio 配置受总开关限制。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<McpViews.Server> create(Authentication auth,
            @PathVariable @Positive long tenantId, @Valid @RequestBody McpRequests.ServerInput input) {
        return ApiResponse.success(servers.create(identity.current(auth), tenantId, input));
    }

    /** 服务详情，鉴权头只返回名称。 */
    @GetMapping("/{serverId}")
    public ApiResponse<McpViews.Server> server(Authentication auth,
            @PathVariable @Positive long tenantId, @PathVariable @Positive long serverId) {
        return ApiResponse.success(servers.server(identity.current(auth), tenantId, serverId));
    }

    /** 管理员局部更新；鉴权头清单整体替换，同名留空沿用原值。 */
    @PatchMapping("/{serverId}")
    public ApiResponse<McpViews.Server> update(Authentication auth,
            @PathVariable @Positive long tenantId, @PathVariable @Positive long serverId,
            @RequestBody Map<String, Object> patch) {
        return ApiResponse.success(servers.update(identity.current(auth), tenantId, serverId, patch));
    }

    /** 管理员删除服务；后续接入模板绑定后改为有绑定先解绑。 */
    @DeleteMapping("/{serverId}")
    public ApiResponse<Void> delete(Authentication auth, @PathVariable @Positive long tenantId,
            @PathVariable @Positive long serverId) {
        servers.delete(identity.current(auth), tenantId, serverId);
        return ApiResponse.success(null);
    }

    /** 管理员用未保存的表单配置探测连接。 */
    @PostMapping("/test")
    public ApiResponse<McpViews.TestResult> test(Authentication auth,
            @PathVariable @Positive long tenantId, @Valid @RequestBody McpRequests.ServerInput input) {
        return ApiResponse.success(servers.test(identity.current(auth), tenantId, input));
    }

    /** 管理员用已保存配置探测连接；密文仅服务端解密。 */
    @PostMapping("/{serverId}/test")
    public ApiResponse<McpViews.TestResult> testSaved(Authentication auth,
            @PathVariable @Positive long tenantId, @PathVariable @Positive long serverId) {
        return ApiResponse.success(
                servers.testSaved(identity.current(auth), tenantId, serverId));
    }
}
