package io.github.chalmery.loopcraft.identity.controller;

import java.util.List;

import io.github.chalmery.loopcraft.identity.dto.IdentityRequests;
import io.github.chalmery.loopcraft.identity.dto.IdentityViews;
import io.github.chalmery.loopcraft.identity.service.IdentityService;
import io.github.chalmery.loopcraft.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 用户资料和平台管理员的账号管理接口。 */
@RestController
@RequestMapping("/api/users")
public class UserController {
    private final IdentityService identity;

    public UserController(IdentityService identity) {
        this.identity = identity;
    }

    /** 查询当前用户。 */
    @GetMapping("/me")
    public ApiResponse<IdentityViews.User> me(Authentication auth) {
        return ApiResponse.success(IdentityViews.User.of(identity.current(auth)));
    }

    /** 修改自己的显示名称。 */
    @PatchMapping("/me")
    public ApiResponse<IdentityViews.User> rename(Authentication auth, @Valid @RequestBody IdentityRequests.Rename request) {
        return ApiResponse.success(IdentityViews.User.of(identity.rename(identity.current(auth), request.name())));
    }

    /** 修改密码并退出，其他会话也会失效。 */
    @PostMapping("/me/password")
    public ApiResponse<Void> password(Authentication auth, @Valid @RequestBody IdentityRequests.ChangePassword request,
            HttpServletRequest servletRequest) {
        identity.changePassword(identity.current(auth), request.oldPassword(), request.newPassword());
        servletRequest.getSession().invalidate();
        SecurityContextHolder.clearContext();
        return ApiResponse.success(null);
    }

    /** 管理员分页查询用户。 */
    @GetMapping
    public ApiResponse<List<IdentityViews.User>> users(Authentication auth,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return ApiResponse.success(identity.users(identity.current(auth), page, size).stream()
                .map(IdentityViews.User::of).toList());
    }

    /** 管理员创建内置账号，不开放自行注册。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<IdentityViews.User> create(Authentication auth,
            @Valid @RequestBody IdentityRequests.CreateUser request) {
        return ApiResponse.success(IdentityViews.User.of(identity.createUser(identity.current(auth), request.username(),
                request.password(), request.displayName())));
    }

    /** 管理员启用或停用普通账号。 */
    @PatchMapping("/{userId}/enabled")
    public ApiResponse<Void> enabled(Authentication auth, @PathVariable @Positive long userId,
            @Valid @RequestBody IdentityRequests.Enabled request) {
        identity.setEnabled(identity.current(auth), userId, request.enabled());
        return ApiResponse.success(null);
    }
}
