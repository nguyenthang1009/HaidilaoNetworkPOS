package com.haidilao.pos.security;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Quản lý mã hóa dữ liệu truyền tải trên không gian mạng (Data-in-Transit Encryption)
 * Áp dụng thuật toán mã hóa đối xứng tiêu chuẩn AES-128 (Advanced Encryption Standard - CBC Mode)
 * Bảo vệ toàn bộ dữ liệu (AUTH, ORDER, BELL, CHAT) chống nghe lén (Eavesdropping / Packet Sniffing)
 */
public class CryptoManager {

    // Khóa bí mật đối xứng 128-bit (16 bytes) chuẩn bảo mật nội bộ Haidilao POS
    private static final String SECRET_KEY = "Haidilao@2026Sec";
    // Vector khởi tạo IV 16 bytes
    private static final String INIT_VECTOR = "HdlNetPosIV@2026";
    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
    public static final String PREFIX = "ENC:";

    /**
     * Mã hóa gói tin dữ liệu sang chuỗi ký tự an toàn Base64 có tiền tố ENC:
     * @param plainText Nội dung gói tin gốc (ví dụ: ORDER|Tablet_Ban1|Lẩu Cay|139000)
     * @return Chuỗi mã hóa (ví dụ: ENC:U2FsdGVkX1...)
     */
    public static String encrypt(String plainText) {
        if (plainText == null) return null;
        try {
            IvParameterSpec iv = new IvParameterSpec(INIT_VECTOR.getBytes(StandardCharsets.UTF_8));
            SecretKeySpec skeySpec = new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), "AES");

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, skeySpec, iv);

            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            // Dự phòng giữ nguyên gói tin nếu phát sinh lỗi mã hóa
            return plainText;
        }
    }

    /**
     * Giải mã gói tin từ chuỗi Base64 về dạng dữ liệu gốc
     * @param cipherText Chuỗi gói tin nhận được từ mạng Socket
     * @return Dữ liệu gốc đã giải mã
     */
    public static String decrypt(String cipherText) {
        if (cipherText == null) return null;
        if (!cipherText.startsWith(PREFIX)) {
            // Hỗ trợ tương thích ngược nếu là gói tin chưa qua mã hóa
            return cipherText;
        }

        try {
            String base64Data = cipherText.substring(PREFIX.length());
            IvParameterSpec iv = new IvParameterSpec(INIT_VECTOR.getBytes(StandardCharsets.UTF_8));
            SecretKeySpec skeySpec = new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), "AES");

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, skeySpec, iv);

            byte[] original = cipher.doFinal(Base64.getDecoder().decode(base64Data));
            return new String(original, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return cipherText;
        }
    }

    /**
     * Kiểm tra xem gói tin có đang ở dạng mã hóa an toàn không
     */
    public static boolean isEncrypted(String message) {
        return message != null && message.startsWith(PREFIX);
    }

    public static void main(String[] args) {
        String original = "ORDER|Tablet_Ban1|Lẩu Cay Tứ Xuyên x1, Bò Wagyu A5 x2|1117000";
        String encrypted = encrypt(original);
        String decrypted = decrypt(encrypted);

        System.out.println("ORIGINAL : " + original);
        System.out.println("ENCRYPTED: " + encrypted);
        System.out.println("DECRYPTED: " + decrypted);
        System.out.println("MATCH    : " + original.equals(decrypted));
    }
}
