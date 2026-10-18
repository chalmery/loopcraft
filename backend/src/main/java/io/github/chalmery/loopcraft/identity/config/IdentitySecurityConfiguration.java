package io.github.chalmery.loopcraft.identity.config;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import io.github.chalmery.loopcraft.identity.model.SessionUser;
import io.github.chalmery.loopcraft.identity.model.UserAccount;
import io.github.chalmery.loopcraft.identity.service.IdentityService;
import io.github.chalmery.loopcraft.shared.ApiResponse;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 配置身份认证和 HTTP 请求保护。
 * 登录成功后，认证信息保存在服务端 Session；后续请求由 Spring Security 从 Session 恢复。
 */
@Configuration(proxyBeanMethods = false)
public class IdentitySecurityConfiguration {
    /** 使用项目自己的账号表校验用户名和密码，不使用 Spring 默认生成的临时用户。 */
    @Bean
    AuthenticationManager authenticationManager(IdentityService identity) {
        return request -> {
            UserAccount user = identity.login(request.getName(), (String) request.getCredentials());
            // Session 只保存用户 ID 和凭证版本，不把密码放进认证信息。
            return UsernamePasswordAuthenticationToken.authenticated(
                    new SessionUser(user.getId(), user.getCredentialVersion()), null, List.of());
        };
    }

    /** 密码只保存 PBKDF2 摘要；前缀记录算法，便于将来迁移编码方式。 */
    @Bean
    PasswordEncoder passwordEncoder() {
        String algorithm = "pbkdf2@SpringSecurity_v5_8";
        return new DelegatingPasswordEncoder(algorithm,
                Map.of(algorithm, Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }

    /** 将 SecurityContext 放进 HTTP Session，使浏览器后续请求能保持登录状态。 */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * 定义 Spring Security 的 HTTP 过滤链：开放少数匿名接口，其余请求都必须登录。
     * CSRF 防护沿用 Spring Security 默认设置，因此写操作需要有效的 CSRF Token。
     */
    @Bean
    SecurityFilterChain identitySecurity(HttpSecurity http, IdentityService identity,
            SecurityContextRepository contexts, JsonMapper objectMapper) throws Exception {
        return http
                // 使用上面配置的 Session 仓库读取和保存登录上下文。
                .securityContext(context -> context.securityContextRepository(contexts))
                // 这是 API 后端，不需要把未登录请求保存下来并在登录后跳回原页面。
                .requestCache(RequestCacheConfigurer::disable)
                .authorizeHttpRequests(requests -> requests
                        // 错误派发请求放行，避免异常处理再次触发认证拦截。
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // 获取 CSRF Token、登录和健康检查允许匿名访问。
                        .requestMatchers("/api/auth/csrf", "/api/auth/login", "/actuator/health").permitAll()
                        .anyRequest().access((authentication, request) -> {
                            // 除了确认请求已认证，也检查账号是否仍有效、密码版本是否变化。
                            identity.current(authentication.get());
                            return new AuthorizationDecision(true);
                        }))
                .exceptionHandling(errors -> errors
                        // 未登录访问受保护接口时返回 401；已登录但权限或 CSRF 校验失败返回 403。
                        .authenticationEntryPoint((request, response, exception) -> writeResponse(response, objectMapper,
                                401, ApiResponse.failure(401, "请先登录")))
                        .accessDeniedHandler((request, response, exception) -> {
                            // 只有 CSRF 过滤器拒绝的请求允许前端刷新令牌后重试。
                            boolean csrfFailure = exception instanceof CsrfException;
                            writeResponse(response, objectMapper, 403, ApiResponse.failure(
                                    csrfFailure ? 40301 : 403, csrfFailure ? "CSRF 令牌已失效" : "权限不足"));
                        }))
                // 退出由过滤链处理：使 Session 中的登录状态失效，并返回统一响应信封。
                .logout(logout -> logout.logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((request, response, authentication) -> writeResponse(response,
                                objectMapper, 200, ApiResponse.success(null))))
                .build();
    }

    /** 安全过滤器产生的响应也使用统一信封。 */
    private static void writeResponse(HttpServletResponse response, JsonMapper objectMapper, int status,
            ApiResponse<?> body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
