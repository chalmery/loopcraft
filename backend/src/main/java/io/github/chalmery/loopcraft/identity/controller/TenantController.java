package io.github.chalmery.loopcraft.identity.controller;

import java.util.List;

import io.github.chalmery.loopcraft.identity.dto.IdentityRequests;
import io.github.chalmery.loopcraft.identity.dto.IdentityViews;
import io.github.chalmery.loopcraft.identity.model.Tenant;
import io.github.chalmery.loopcraft.identity.service.IdentityService;
import io.github.chalmery.loopcraft.shared.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
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

/** 租户及成员管理，权限只在当前租户内生效。 */
@RestController
@RequestMapping("/api")
public class TenantController {
    private final IdentityService identity;

    public TenantController(IdentityService identity) {
        this.identity = identity;
    }

    /** 管理员创建租户并指定负责人。 */
    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Tenant> create(Authentication auth, @Valid @RequestBody IdentityRequests.CreateTenant request) {
        return ApiResponse.success(identity.createTenant(identity.current(auth), request.name(), request.ownerUserId()));
    }

    /** 查询可访问的租户，管理员可查看全部。 */
    @GetMapping("/tenants")
    public ApiResponse<List<IdentityViews.AccessibleTenant>> list(Authentication auth,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return ApiResponse.success(identity.accessibleTenants(identity.current(auth), page, size));
    }

    /** 查看租户信息。 */
    @GetMapping("/tenants/{tenantId}")
    public ApiResponse<Tenant> get(Authentication auth, @PathVariable @Positive long tenantId) {
        return ApiResponse.success(identity.tenant(identity.current(auth), tenantId, false));
    }

    /** 当前租户负责人修改租户名称。 */
    @PatchMapping("/tenants/{tenantId}")
    public ApiResponse<Tenant> rename(Authentication auth, @PathVariable @Positive long tenantId,
            @Valid @RequestBody IdentityRequests.Rename request) {
        return ApiResponse.success(identity.renameTenant(identity.current(auth), tenantId, request.name()));
    }

    /** 分页查询当前租户成员。 */
    @GetMapping("/tenants/{tenantId}/members")
    public ApiResponse<List<IdentityViews.Member>> members(Authentication auth,
            @PathVariable @Positive long tenantId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return ApiResponse.success(identity.members(identity.current(auth), tenantId, page, size));
    }

    /** 查询可添加到当前租户的启用账号。 */
    @GetMapping("/tenants/{tenantId}/member-candidates")
    public ApiResponse<List<IdentityViews.MemberCandidate>> memberCandidates(Authentication auth,
            @PathVariable @Positive long tenantId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return ApiResponse.success(identity.memberCandidates(identity.current(auth), tenantId, page, size));
    }

    /** 当前租户负责人添加已有账号为成员。 */
    @PostMapping("/tenants/{tenantId}/members")
    public ApiResponse<Void> add(Authentication auth, @PathVariable @Positive long tenantId,
            @Valid @RequestBody IdentityRequests.AddMember request) {
        identity.addMember(identity.current(auth), tenantId, request.userId());
        return ApiResponse.success(null);
    }

    /** 移除普通成员，首版不支持移除负责人。 */
    @DeleteMapping("/tenants/{tenantId}/members/{userId}")
    public ApiResponse<Void> remove(Authentication auth, @PathVariable @Positive long tenantId,
            @PathVariable @Positive long userId) {
        identity.removeMember(identity.current(auth), tenantId, userId);
        return ApiResponse.success(null);
    }
}
