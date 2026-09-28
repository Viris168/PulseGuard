package com.viris.PulseGuard.apikey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Generating and fingerprinting key material. No state beyond the random source. */
public final class ApiKeySecrets {

    /** Recognisable in logs and by secret scanners (GitHub push protection matches on prefixes). */
    public static final String PREFIX = "pg_live_";
    /** 32 base62 characters, about 190 bits: far beyond guessing, so SHA-256 alone is enough. */
    static final int RANDOM_LENGTH = 32;
    /** Shown in the list: the prefix plus four characters. */
    static final int DISPLAY_LENGTH = PREFIX.length() + 4;

    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeySecrets() {
    }

    public static String generate() {
        StringBuilder key = new StringBuilder(PREFIX.length() + RANDOM_LENGTH).append(PREFIX);
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            // nextInt(bound) is uniform; "byte % 62" would favour the first characters.
            key.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return key.toString();
    }

    public static String displayPrefix(String key) {
        return key.substring(0, DISPLAY_LENGTH);
    }

    /** Hex SHA-256; what is stored and what incoming keys are looked up by. */
    public static String hash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }

    /** Cheap shape check before touching the database: wrong length or prefix is never a key. */
    public static boolean looksLikeKey(String token) {
        return token.length() == PREFIX.length() + RANDOM_LENGTH && token.startsWith(PREFIX);
    }
}
