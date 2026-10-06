package com.supermarket.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Passwords are stored with BCrypt (salted and slow, so a leaked backup does not reveal them).
 * Accounts created before BCrypt still have an unsalted SHA-256 hash; they keep working and are
 * upgraded the next time the user signs in.
 */
public class PasswordUtil {

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private static final Pattern LEGACY_SHA256 = Pattern.compile("[0-9a-f]{64}");

    private PasswordUtil() {
    }

    public static String hash(String password) {
        return BCRYPT.encode(password);
    }

    public static boolean matches(String password, String storedHash) {
        if (password == null || storedHash == null) {
            return false;
        }
        if (isLegacyHash(storedHash)) {
            return MessageDigest.isEqual(
                    sha256Hex(password).getBytes(StandardCharsets.US_ASCII),
                    storedHash.getBytes(StandardCharsets.US_ASCII));
        }
        return BCRYPT.matches(password, storedHash);
    }

    public static boolean needsUpgrade(String storedHash) {
        return storedHash != null && isLegacyHash(storedHash);
    }

    /** SHA-256 in hex. Used for legacy passwords and to store session tokens, which are already random. */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static boolean isLegacyHash(String storedHash) {
        return LEGACY_SHA256.matcher(storedHash).matches();
    }
}
