package com.smarthospital.platform.iam;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * Minimal RFC 6238 TOTP (SHA-1, 6 digits, 30-second step) used for optional
 * MFA on sensitive roles (PD-008). No third-party dependency.
 */
public final class TotpService {

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TotpService() {
    }

    public static String generateSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return base32Encode(bytes);
    }

    public static boolean verify(String secretBase32, String code, int window) {
        if (secretBase32 == null || code == null || code.isBlank()) {
            return false;
        }
        String normalized = code.replace(" ", "");
        if (normalized.length() != DIGITS) {
            return false;
        }
        long counter = System.currentTimeMillis() / 1000L / STEP_SECONDS;
        for (int offset = -window; offset <= window; offset++) {
            if (normalized.equals(generate(secretBase32, counter + offset))) {
                return true;
            }
        }
        return false;
    }

    public static String generate(String secretBase32, long counter) {
        try {
            byte[] key = base32Decode(secretBase32);
            byte[] data = ByteBuffer.allocate(8).putLong(counter).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int otp = binary % 1_000_000;
            return String.format("%06d", otp);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("TOTP is unavailable", ex);
        }
    }

    public static String otpauthUrl(String issuer, String account, String secretBase32) {
        return "otpauth://totp/" + urlEncode(issuer) + ":" + urlEncode(account)
                + "?secret=" + secretBase32 + "&issuer=" + urlEncode(issuer)
                + "&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    public static String base32Encode(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return out.toString();
    }

    public static byte[] base32Decode(String value) {
        String normalized = value.replace("=", "").replace(" ", "").toUpperCase();
        int buffer = 0;
        int bits = 0;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < normalized.length(); i++) {
            int index = BASE32.indexOf(normalized.charAt(i));
            if (index < 0) {
                throw new IllegalArgumentException("Invalid base32 character");
            }
            buffer = (buffer << 5) | index;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
