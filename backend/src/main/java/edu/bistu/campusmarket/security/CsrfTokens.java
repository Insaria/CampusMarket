package edu.bistu.campusmarket.security;

import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class CsrfTokens {
    public static final String HEADER_NAME = "X-CSRF-TOKEN";
    private static final String ATTRIBUTE = CsrfTokens.class.getName();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_LENGTH = 43;

    private CsrfTokens() {
    }

    public static String getOrCreate(HttpSession session) {
        synchronized (session) {
            if (session.getAttribute(ATTRIBUTE) instanceof String existing) {
                return existing;
            }
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            session.setAttribute(ATTRIBUTE, token);
            return token;
        }
    }

    static boolean matches(HttpSession session, String supplied) {
        if (session == null || supplied == null || supplied.length() != TOKEN_LENGTH) {
            return false;
        }
        try {
            return session.getAttribute(ATTRIBUTE) instanceof String expected
                    && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                            supplied.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalStateException exception) {
            return false;
        }
    }
}
