package com.smarthospital.platform.iam;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * PBKDF2WithHmacSHA256 password hashing (ADR-004).
 *
 * <p>Parameters are stored per user so the cost can be raised later without
 * invalidating existing hashes. The default matches the security baseline:
 * 600,000 iterations, 16-byte salt, 256-bit derived key.
 */
public final class PasswordHasher {

    public static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    public static final int DEFAULT_ITERATIONS = 600_000;
    public static final int SALT_BYTES = 16;
    public static final int KEY_BITS = 256;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    public static byte[] newSalt() {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return salt;
    }

    public static String hash(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            byte[] derived = factory.generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(derived);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
            throw new IllegalStateException("PBKDF2 is unavailable in this JDK", ex);
        } finally {
            spec.clearPassword();
        }
    }

    public static boolean verify(String password, byte[] salt, int iterations, String expectedHash) {
        if (password == null || salt == null || expectedHash == null) {
            return false;
        }
        String actual = hash(password, salt, iterations);
        return MessageDigest.isEqual(
                actual.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                expectedHash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static boolean needsRehash(String algorithm, int iterations) {
        return !ALGORITHM.equals(algorithm) || iterations < DEFAULT_ITERATIONS;
    }
}
