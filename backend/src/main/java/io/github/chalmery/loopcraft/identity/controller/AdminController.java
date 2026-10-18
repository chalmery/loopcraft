package io.github.chalmery.loopcraft.identity.controller;

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

/** 后台配置：所有入口在业务层验证平台管理员权限。 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final IdentityService identity;

    public AdminController(IdentityService identity) {
        this.identity = identity;
    }

    /** 平台管理员分页查询账号。 */
    @GetMapping("/users")
    public ApiResponse<IdentityViews.PageResult<IdentityViews.User>> users(Authentication auth,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        return ApiResponse.success(identity.userPage(identity.current(auth), page, size));
    }

    /** 平台管理员新增普通账号。 */
    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<IdentityViews.User> createUser(Authentication auth,
            @Valid @RequestBody IdentityRequests.CreateUser request) {
        return ApiResponse.success(IdentityViews.User.of(identity.createUser(identity.current(auth), request.username(),
                request.password(), request.displayName())));
    }

    /** 编辑账号资料与状态，不允许修改平台身份。 */
    @PatchMapping("/users/{id}")
    public ApiResponse<IdentityViews.User> updateUser(Authentication auth, @PathVariable @Positive long id,
            @Valid @RequestBody IdentityRequests.UpdateUser request) {
        return ApiResponse.success(IdentityViews.User.of(identity.updateUser(identity.current(auth), id,
                request.username(), request.displayName(), request.enabled())));
    }

    /** 删除账号，保护平台管理员和现任租户负责人。 */
    @DeleteMapping("/users/{id}")
    public ApiResponse<Void> deleteUser(Authentication auth, @PathVariable @Positive long id) {
        identity.deleteUser(identity.current(auth), id);
        return ApiResponse.success(null);
    }

    /** 平台管理员分页查询租户及负责人。 */
    @GetMapping("/tenants")
    public ApiResponse<IdentityViews.PageResult<IdentityViews.ManagedTenant>> tenants(Authentication auth,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        return ApiResponse.success(identity.tenantPage(identity.current(auth), page, size));
    }

    /** 新增租户并指定负责人。 */
    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Tenant> createTenant(Authentication auth,
            @Valid @RequestBody IdentityRequests.CreateTenant request) {
        return ApiResponse.success(identity.createTenant(identity.current(auth), request.name(), request.ownerUserId()));
    }

    /** 编辑租户名称及负责人，交接在同一事务内完成。 */
    @PatchMapping("/tenants/{id}")
    public ApiResponse<Tenant> updateTenant(Authentication auth, @PathVariable @Positive long id,
            @Valid @RequestBody IdentityRequests.CreateTenant request) {
        return ApiResponse.success(identity.updateTenant(identity.current(auth), id, request.name(), request.ownerUserId()));
    }

    /** 删除租户，关联业务数据由数据库外键保护。 */
    @DeleteMapping("/tenants/{id}")
    public ApiResponse<Void> deleteTenant(Authentication auth, @PathVariable @Positive long id) {
        identity.deleteTenant(identity.current(auth), id);
        return ApiResponse.success(null);
    }
}
