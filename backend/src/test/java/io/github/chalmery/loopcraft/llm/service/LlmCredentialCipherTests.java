package io.github.chalmery.loopcraft.llm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Base64;

class LlmCredentialCipherTests {

    private final String key = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void randomizesNonceAndAuthenticatesTenantAndCiphertext() {
        var cipher = new LlmCredentialCipher(key);
        String first = cipher.encrypt(10, "test-only-key");
        assertThat(first).startsWith("v1.").doesNotContain("test-only-key");
        assertThat(cipher.encrypt(10, "test-only-key")).isNotEqualTo(first);
        assertThat(cipher.decrypt(10, first)).isEqualTo("test-only-key");
        assertThatThrownBy(() -> cipher.decrypt(11, first))
                .isInstanceOf(ResponseStatusException.class);
        byte[] packed = Base64.getDecoder().decode(first.substring(3));
        packed[packed.length - 1] ^= 1;
        assertThatThrownBy(
                        () ->
                                cipher.decrypt(
                                        10, "v1." + Base64.getEncoder().encodeToString(packed)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageNotContaining("test-only-key");
    }

    @Test
    void refusesMissingInvalidAndWrongMasterKeys() {
        assertThatThrownBy(() -> new LlmCredentialCipher("not-base64"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new LlmCredentialCipher("AA=="))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new LlmCredentialCipher("").encrypt(1, "test-only-key"))
                .isInstanceOf(ResponseStatusException.class);
        byte[] other = new byte[32];
        other[0] = 1;
        String encrypted = new LlmCredentialCipher(key).encrypt(1, "test-only-key");
        assertThatThrownBy(
                        () ->
                                new LlmCredentialCipher(Base64.getEncoder().encodeToString(other))
                                        .decrypt(1, encrypted))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void validatesApiRootsAndNeverTreatsEndpointUrlsAsRoots() {
        assertThat(LlmInputs.baseUrl(" https://example.test/custom/v1/ "))
                .isEqualTo("https://example.test/custom/v1");
        assertThat(LlmInputs.baseUrl("http://127.0.0.1:1234")).isEqualTo("http://127.0.0.1:1234");
        for (String url :
                new String[] {
                    "https://example.test/v1/models/",
                    "https://example.test/v1/messages",
                    "https://example.test/v1/chat/completions",
                    "https://user:secret@example.test/v1",
                    "file:///tmp/a",
                    "https://example.test/v1?api_key=secret",
                    "https://example.test/v1#x"
                }) {
            assertThatThrownBy(() -> LlmInputs.baseUrl(url))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageNotContaining("secret");
        }
    }
}
