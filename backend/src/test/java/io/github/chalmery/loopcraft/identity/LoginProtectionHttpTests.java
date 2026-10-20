package io.github.chalmery.loopcraft.identity;

import java.util.Locale;
import java.util.UUID;

import io.github.chalmery.loopcraft.identity.mapper.UserMapper;
import io.github.chalmery.loopcraft.identity.model.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "loopcraft.identity.login-protection.account-failures=2",
        "loopcraft.identity.login-protection.ip-attempts=3"
})
@AutoConfigureMockMvc
class LoginProtectionHttpTests {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private PasswordEncoder passwords;
    @MockitoBean
    private UserMapper users;

    @Test
    void blockedAccountsReturnUniform429WithoutDatabaseAccessOrNewSessionBypass() throws Exception {
        String username = UUID.randomUUID().toString();
        // 未知账号和新 Session 也参与限制，大小写和来源变更无法重置账号计数。
        mvc.perform(login(username, "192.0.2.1", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(login(username.toUpperCase(Locale.ROOT), "192.0.2.2", "wrong"))
                .andExpect(status().isUnauthorized());
        clearInvocations(users);
        mvc.perform(login(username, "192.0.2.3", "correct-password"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "900"))
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message").value("登录尝试过于频繁，请稍后重试"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.data").isEmpty());
        verifyNoInteractions(users);
    }

    @Test
    void forwardedHeadersCannotBypassSourceLimitWhenSwitchingAccounts() throws Exception {
        for (int index = 0; index < 3; index++) {
            mvc.perform(login(UUID.randomUUID().toString(), "192.0.2.10", "wrong")
                    .header("X-Forwarded-For", "198.51.100." + index)
                    .header("Forwarded", "for=198.51.100." + index)).andExpect(status().isUnauthorized());
        }
        clearInvocations(users);
        mvc.perform(login(UUID.randomUUID().toString(), "192.0.2.10", "wrong")
                .header("X-Forwarded-For", "203.0.113.1")
                .header("Forwarded", "for=203.0.113.1"))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        verifyNoInteractions(users);
        mvc.perform(login(UUID.randomUUID().toString(), "192.0.2.11", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disabledAccountWithCorrectPasswordIsStillThrottled() throws Exception {
        String username = UUID.randomUUID().toString();
        when(users.selectOne(any())).thenReturn(new UserAccount(42L, username,
                passwords.encode("correct-password"), "停用账号", false, false, 0));
        for (int index = 0; index < 2; index++) {
            mvc.perform(login(username, "192.0.2.20", "correct-password"))
                    .andExpect(status().isUnauthorized());
        }
        clearInvocations(users);
        mvc.perform(login(username, "192.0.2.21", "correct-password"))
                .andExpect(status().isTooManyRequests());
        verifyNoInteractions(users);
    }

    private MockHttpServletRequestBuilder login(String username, String ip, String password) {
        return post("/api/auth/login").with(csrf()).with(request -> {
            request.setRemoteAddr(ip);
            return request;
        }).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    }
}
