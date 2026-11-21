package com.smarthospital.shared.id;

import java.security.SecureRandom;

/**
 * ULID generator (48-bit timestamp + 80-bit randomness, Crockford Base32).
 *
 * <p>ULIDs are the public identifiers exposed by the API. Internal primary
 * keys remain numeric and are never returned to clients (ADR-007).
 *
 * <p>This implementation is not monotonic within the same millisecond. That is
 * acceptable because uniqueness, not ordering, is the contract. If ordered
 * identifiers are required later, replace this class behind the same API.
 */
public final class Ulid {

    private static final char[] ENCODING =
            "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ulid() {
    }

    public static String next() {
        char[] out = new char[26];

        long timestamp = System.currentTimeMillis();
        for (int i = 9; i >= 0; i--) {
            out[i] = ENCODING[(int) (timestamp & 0x1F)];
            timestamp >>>= 5;
        }

        byte[] randomness = new byte[10];
        RANDOM.nextBytes(randomness);

        long high = 0;
        for (int i = 0; i < 5; i++) {
            high = (high << 8) | (randomness[i] & 0xFFL);
        }
        long low = 0;
        for (int i = 5; i < 10; i++) {
            low = (low << 8) | (randomness[i] & 0xFFL);
        }

        // 80 random bits are encoded as 16 Crockford Base32 characters in
        // positions 10..25. The timestamp keeps positions 0..9.
        for (int i = 17; i >= 10; i--) {
            out[i] = ENCODING[(int) (high & 0x1F)];
            high >>>= 5;
        }
        for (int i = 25; i >= 18; i--) {
            out[i] = ENCODING[(int) (low & 0x1F)];
            low >>>= 5;
        }
        return new String(out);
    }

    public static boolean isValid(String value) {
        if (value == null || value.length() != 26) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 'I' || c == 'L' || c == 'O' || c == 'U') {
                return false;
            }
            boolean found = false;
            for (char allowed : ENCODING) {
                if (c == allowed) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }
}
