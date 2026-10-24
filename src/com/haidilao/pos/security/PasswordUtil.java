package com.haidilao.pos.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Băm mật khẩu nhân viên bằng SHA-256 + salt ngẫu nhiên.
 * Database chỉ lưu (hash, salt), không bao giờ lưu mật khẩu gốc.
 */
public final class PasswordUtil {
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordUtil() {}

    /** Sinh salt 16 byte, trả về dạng hex 32 ký tự */
    public static String newSalt() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return toHex(salt);
    }

    /** SHA-256(salt + password), dạng hex 64 ký tự */
    public static String hash(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes(StandardCharsets.UTF_8));
            return toHex(md.digest(password.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM không hỗ trợ SHA-256", e);
        }
    }

    /** So sánh mật khẩu nhập vào với hash đã lưu (so sánh thời gian hằng để tránh đoán dần từng ký tự) */
    public static boolean verify(String password, String salt, String expectedHash) {
        if (password == null || salt == null || expectedHash == null) return false;
        byte[] a = hash(password, salt).getBytes(StandardCharsets.US_ASCII);
        byte[] b = expectedHash.toLowerCase().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(a, b);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte x : bytes) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}
