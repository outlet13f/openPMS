package com.openpms.cicd;

import com.openpms.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Tool tokens and webhook secrets at rest: AES-256-GCM with the key from OPENPMS_SECRET_KEY (32 bytes, Base64). Without a key
 * nothing can be saved (422); a lost key means entering the tokens again (10 운영자 매뉴얼).
 */
@Component
public class SecretBox {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    public SecretBox(@Value("${openpms.secret-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            key = null;
            return;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            // no cause: the decoder's message echoes the offending key character into the start-up log
            throw new IllegalArgumentException("OPENPMS_SECRET_KEY must be Base64 (32 bytes)");
        }
        if (raw.length != 32) throw new IllegalArgumentException("OPENPMS_SECRET_KEY must be 32 bytes (Base64), got " + raw.length);
        key = new SecretKeySpec(raw, "AES");
    }

    public boolean ready() {
        return key != null;
    }

    public void requireReady() {
        if (key == null) throw new ApiException(422, "암호화 키(OPENPMS_SECRET_KEY)가 설정되지 않았습니다.");
    }

    public String seal(String plain) {
        requireReady();
        try {
            var iv = new byte[12];
            RANDOM.nextBytes(iv);
            var c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            var ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            var b64 = Base64.getEncoder();
            return "v1:" + b64.encodeToString(iv) + ":" + b64.encodeToString(ct);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("seal failed", e);
        }
    }

    public String open(String sealed) {
        requireReady();
        var parts = sealed.split(":");
        if (parts.length != 3 || !"v1".equals(parts[0])) throw new IllegalStateException("unknown secret format");
        try {
            var b64 = Base64.getDecoder();
            var c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, b64.decode(parts[1])));
            return new String(c.doFinal(b64.decode(parts[2])), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("secret could not be opened (wrong key or changed value)");
        }
    }

    public static String hint(String plain) {
        return plain == null || plain.length() <= 4 ? "****" : plain.substring(plain.length() - 4);
    }
}
