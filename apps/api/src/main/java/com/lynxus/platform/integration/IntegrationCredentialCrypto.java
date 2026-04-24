package com.lynxus.platform.integration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class IntegrationCredentialCrypto {
    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final ObjectMapper objectMapper;
    private final String rawKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public IntegrationCredentialCrypto(
        ObjectMapper objectMapper,
        @Value("${lynxus.integration.credentials.encryption-key:}") String rawKey
    ) {
        this.objectMapper = objectMapper;
        this.rawKey = rawKey == null ? "" : rawKey.trim();
    }

    public EncryptedCredential encrypt(Map<String, Object> credential) {
        if (credential == null || credential.isEmpty()) {
            return new EncryptedCredential(null, null);
        }
        if (rawKey.isBlank()) {
            throw new IllegalStateException("lynxus.integration.credentials.encryption-key must be configured before storing integration credentials");
        }
        try {
            String plaintext = objectMapper.writeValueAsString(credential);
            byte[] nonce = new byte[NONCE_BYTES];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new EncryptedCredential(
                "v1:" + Base64.getEncoder().encodeToString(nonce) + ":" + Base64.getEncoder().encodeToString(ciphertext),
                fingerprint(plaintext)
            );
        } catch (Exception error) {
            throw new IllegalStateException("failed to encrypt integration credential", error);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return Map.of();
        }
        if (rawKey.isBlank()) {
            throw new IllegalStateException("lynxus.integration.credentials.encryption-key must be configured before reading integration credentials");
        }
        try {
            String[] parts = ciphertext.split(":", 3);
            if (parts.length != 3 || !"v1".equals(parts[0])) {
                throw new IllegalArgumentException("unsupported integration credential ciphertext");
            }
            byte[] nonce = Base64.getDecoder().decode(parts[1]);
            byte[] encrypted = Base64.getDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] plaintext = cipher.doFinal(encrypted);
            Object decoded = objectMapper.readValue(new String(plaintext, StandardCharsets.UTF_8), Object.class);
            if (!(decoded instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("integration credential must decode to an object");
            }
            return (Map<String, Object>) map;
        } catch (Exception error) {
            throw new IllegalStateException("failed to decrypt integration credential", error);
        }
    }

    private SecretKeySpec keySpec() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawKey.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(Arrays.copyOf(digest, 32), "AES");
    }

    private String fingerprint(String plaintext) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(keySpec().getEncoded(), "HmacSHA256"));
        return "v1:" + Base64.getEncoder().encodeToString(mac.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
    }

    public record EncryptedCredential(String ciphertext, String fingerprint) {
    }
}
