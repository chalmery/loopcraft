package io.github.chalmery.loopcraft.identity;

import io.github.chalmery.loopcraft.identity.mapper.TenantMemberMapper;
import io.github.chalmery.loopcraft.identity.mapper.UserMapper;
import io.github.chalmery.loopcraft.identity.model.UserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class IdentitySecurityTests {
    private static final String PASSWORD = "test-password-123";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private PasswordEncoder passwords;
    @MockitoBean
    private UserMapper mapper;
    @MockitoBean
    private TenantMemberMapper members;
    private UserAccount user;

    @BeforeEach
    void account() {
        user = new UserAccount(42L, "tester", passwords.encode(PASSWORD), "测试用户", false, true, 0);
        when(mapper.selectById(42L)).thenReturn(user);
        when(mapper.selectOne(any())).thenReturn(user);
    }

    @Test
    void protectsApiAndKeepsHealthAndCsrfPublic() throws Exception {
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.headerName").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        mvc.perform(post("/api/agent/chat").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"你好\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginRequiresCsrfAndDoesNotExposeCredentials() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40301));
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(login()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("tester"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.credentialVersion").doesNotExist());
    }

    @Test
    void rotatesSessionIdPersistsAuthenticationAndLogsOut() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String before = session.getId();
        mvc.perform(post("/api/auth/login").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(login())).andExpect(status().isOk());
        assertThat(session.getId()).isNotEqualTo(before);
        mvc.perform(get("/api/users/me").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("success"));
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void staleCsrfDoesNotExecuteLogoutAndFreshTokenCanRetry() throws Exception {
        var initial = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) initial.getRequest().getSession(false);
        var oldToken = (CsrfToken) initial.getRequest().getAttribute(CsrfToken.class.getName());
        String oldValue = oldToken.getToken();
        mvc.perform(post("/api/auth/login").session(session).header(oldToken.getHeaderName(), oldValue)
                .contentType(MediaType.APPLICATION_JSON).content(login())).andExpect(status().isOk());

        mvc.perform(post("/api/auth/logout").session(session).header(oldToken.getHeaderName(), oldValue))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40301));
        mvc.perform(get("/api/users/me").session(session)).andExpect(status().isOk());

        var refreshed = mvc.perform(get("/api/auth/csrf").session(session))
                .andExpect(status().isOk()).andReturn();
        var newToken = (CsrfToken) refreshed.getRequest().getAttribute(CsrfToken.class.getName());
        mvc.perform(post("/api/auth/logout").session(session)
                .header(newToken.getHeaderName(), newToken.getToken())).andExpect(status().isOk());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void rejectsUnknownWrongAndDisabledCredentialsWithSameStatus() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(login().replace(PASSWORD, "wrong"))).andExpect(status().isUnauthorized());
        when(mapper.selectOne(any())).thenReturn(null);
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(login().replace("tester", "missing"))).andExpect(status().isUnauthorized());
        when(mapper.selectOne(any())).thenReturn(new UserAccount(42L, "tester", user.getPasswordHash(),
                "测试用户", false, false, 0));
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(login()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokedCredentialsAndDisabledAccountInvalidateExistingSession() throws Exception {
        for (UserAccount changed : new UserAccount[]{
                new UserAccount(42L, "tester", user.getPasswordHash(), "测试用户", false, true, 1),
                new UserAccount(42L, "tester", user.getPasswordHash(), "测试用户", false, false, 0)}) {
            when(mapper.selectById(42L)).thenReturn(changed);
            mvc.perform(get("/api/users/me").with(authentication(IdentityTestSupport.authentication())))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void rejectsPrivilegeEscalationAndTenantCrossAccess() throws Exception {
        mvc.perform(post("/api/users").with(authentication(IdentityTestSupport.authentication())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"newuser\",\"password\":\"test-password-123\",\"displayName\":\"新用户\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        mvc.perform(get("/api/tenants/999").with(authentication(IdentityTestSupport.authentication())))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsInvalidFieldsWithoutEchoingPassword() throws Exception {
        when(mapper.selectById(42L)).thenReturn(new UserAccount(42L, "tester", user.getPasswordHash(), "管理员", true, true, 0));
        mvc.perform(post("/api/users").with(authentication(IdentityTestSupport.authentication())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"x\",\"password\":\"secret\",\"displayName\":\"用户\"}"))
                .andExpect(status().isBadRequest()).andExpect(content().string(not(containsString("secret"))));
        mvc.perform(get("/api/users?size=1000").with(authentication(IdentityTestSupport.authentication())))
                .andExpect(status().isBadRequest());
    }

    private String login() {
        return "{\"username\":\"tester\",\"password\":\"" + PASSWORD + "\"}";
    }

    @Test
    void rejectsMalformedNumericParametersWithoutEchoingTheirValues() throws Exception {
        when(mapper.selectById(42L)).thenReturn(new UserAccount(42L, "tester", user.getPasswordHash(), "管理员", true, true, 0));
        for (String path : new String[]{"/api/users?page=private-value", "/api/tenants/private-value",
                "/api/admin/users?size=999999999999999999999"}) {
            mvc.perform(get(path).with(authentication(IdentityTestSupport.authentication())))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400))
                    .andExpect(content().string(not(containsString("private-value"))));
        }
    }
}
