package edu.bistu.campusmarket.security;

import edu.bistu.campusmarket.exception.BusinessException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@Profile("db")
public class BankAccountEncryption {
    private static final byte FORMAT_VERSION = 1;
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public BankAccountEncryption(@Value("${BANK_ACCOUNT_ENCRYPTION_KEY:}") String configuredKey) {
        byte[] decoded = decode(configuredKey);
        key = decoded != null && decoded.length == 32 ? new SecretKeySpec(decoded, "AES") : null;
        if (decoded != null) {
            Arrays.fill(decoded, (byte) 0);
        }
    }

    public byte[] encrypt(String bankAccount) {
        if (key == null) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "REGISTRATION_UNAVAILABLE", "注册暂不可用");
        }
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        byte[] plaintext = bankAccount.getBytes(StandardCharsets.US_ASCII);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(new byte[]{FORMAT_VERSION});
            byte[] ciphertext = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(1 + nonce.length + ciphertext.length)
                    .put(FORMAT_VERSION).put(nonce).put(ciphertext).array();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not encrypt registration data");
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private static byte[] decode(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
