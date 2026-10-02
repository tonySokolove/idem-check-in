package com.idem.checkin.security;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** Генерация непредсказуемых токенов и их хеширование для хранения. */
@Component
public class Secrets {

    private static final Base64.Encoder URL_SAFE = Base64.getUrlEncoder().withoutPadding();

    private final SecureRandom random;

    public Secrets(SecureRandom random) {
        this.random = random;
    }

    /** Токен сессии Station Agent: 256 бит. */
    public String sessionToken() {
        return token(32);
    }

    /** Public token для ссылки на бейдже: 128 бит, 22 символа — помещается на любую NFC-метку. */
    public String publicCardToken() {
        return token(16);
    }

    public static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    public static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private String token(int bytes) {
        byte[] buffer = new byte[bytes];
        random.nextBytes(buffer);
        return URL_SAFE.encodeToString(buffer);
    }
}
