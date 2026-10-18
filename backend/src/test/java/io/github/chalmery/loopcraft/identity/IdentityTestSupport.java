package io.github.chalmery.loopcraft.identity;

import java.util.List;

import io.github.chalmery.loopcraft.identity.model.SessionUser;
import io.github.chalmery.loopcraft.identity.model.UserAccount;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

public final class IdentityTestSupport {
    public static final UserAccount USER = new UserAccount(42L, "tester", "unused", "测试用户", false, true, 0);

    private IdentityTestSupport() {
    }

    public static Authentication authentication() {
        return UsernamePasswordAuthenticationToken.authenticated(new SessionUser(42, 0), null, List.of());
    }
}
