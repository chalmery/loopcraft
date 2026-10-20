package io.github.chalmery.loopcraft.identity.controller;

import io.github.chalmery.loopcraft.identity.dto.IdentityRequests;
import io.github.chalmery.loopcraft.identity.dto.IdentityViews;
import io.github.chalmery.loopcraft.identity.service.IdentityService;
import io.github.chalmery.loopcraft.identity.service.LoginProtection;
import io.github.chalmery.loopcraft.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 内置账号登录；退出接口由 Spring Security 处理。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final IdentityService identity;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contexts;
    private final LoginProtection loginProtection;

    public AuthController(IdentityService identity, AuthenticationManager authenticationManager,
            SecurityContextRepository contexts, LoginProtection loginProtection) {
        this.identity = identity;
        this.authenticationManager = authenticationManager;
        this.contexts = contexts;
        this.loginProtection = loginProtection;
    }

    /** 获取写操作所需的 CSRF 令牌，登录和退出后需重新获取。 */
    @GetMapping("/csrf")
    public ApiResponse<IdentityViews.Csrf> csrf(CsrfToken token) {
        return ApiResponse.success(new IdentityViews.Csrf(token.getHeaderName(), token.getToken()));
    }

    /** 用户名密码登录，轮换会话 ID 并保存登录状态。 */
    @PostMapping("/login")
    public ApiResponse<IdentityViews.User> login(@Valid @RequestBody IdentityRequests.Login request,
            HttpServletRequest servletRequest, HttpServletResponse response) {
        // 使用服务端看到的对端地址，不直接信任客户端提供的转发头。
        var authentication = loginProtection.authenticate(request.username(), servletRequest.getRemoteAddr(),
                () -> authenticationManager.authenticate(
                        UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password())));
        new ChangeSessionIdAuthenticationStrategy().onAuthentication(authentication, servletRequest, response);
        new CsrfAuthenticationStrategy(new HttpSessionCsrfTokenRepository())
                .onAuthentication(authentication, servletRequest, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, servletRequest, response);
        return ApiResponse.success(IdentityViews.User.of(identity.current(authentication)));
    }
}
