package io.github.chalmery.loopcraft.llm.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** AES-256-GCM，租户作为附加认证数据，密文不可跨租户搬用。 */
@Component
public class LlmCredentialCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public LlmCredentialCipher(@Value("${loopcraft.llm.encryption-key:}") String encoded) {
        SecretKeySpec configured = null;
        if (!encoded.isBlank()) {
            try {
                byte[] bytes = Base64.getDecoder().decode(encoded);
                if (bytes.length != 32) throw new IllegalArgumentException();
                configured = new SecretKeySpec(bytes, "AES");
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("LLM 加密主密钥必须为 Base64 编码的 32 字节");
            }
        }
        key = configured;
    }

    public String encrypt(long tenantId, String plaintext) {
        requireKey();
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, tenantId, nonce);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, packed, 0, nonce.length);
            System.arraycopy(encrypted, 0, packed, nonce.length, encrypted.length);
            return "v1." + Base64.getEncoder().encodeToString(packed);
        } catch (GeneralSecurityException exception) {
            throw unavailable();
        }
    }

    public String decrypt(long tenantId, String ciphertext) {
        requireKey();
        try {
            if (!ciphertext.startsWith("v1.")) throw new IllegalArgumentException();
            byte[] packed = Base64.getDecoder().decode(ciphertext.substring(3));
            if (packed.length < 29) throw new IllegalArgumentException();
            byte[] nonce = java.util.Arrays.copyOf(packed, 12);
            return new String(cipher(Cipher.DECRYPT_MODE, tenantId, nonce)
                    .doFinal(packed, 12, packed.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw unavailable();
        }
    }

    private Cipher cipher(int mode, long tenantId, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(Long.toString(tenantId).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private void requireKey() {
        if (key == null) throw unavailable();
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模型凭证加密服务不可用，请检查主密钥配置");
    }
}
