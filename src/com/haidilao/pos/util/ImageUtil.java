package com.haidilao.pos.util;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.swing.ImageIcon;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/**
 * Xử lý ảnh món ăn: thu nhỏ ảnh trước khi lưu MySQL / gửi qua Socket, và đổi byte[] thành ảnh để hiển thị
 */
public final class ImageUtil {
    // Kích thước khung ảnh hiển thị trên thẻ món (đơn vị điểm ảnh logic)
    public static final int DISPLAY_WIDTH = 200;
    public static final int DISPLAY_HEIGHT = 150;

    // Lưu ảnh gấp đôi khung hiển thị: màn hình Windows phóng to 125% - 200% vẫn nét
    private static final int STORE_SCALE = 2;
    private static final float JPEG_QUALITY = 0.9f;

    private ImageUtil() {}

    /**
     * Đọc file ảnh (jpg, png, gif, bmp), thu nhỏ vừa khung 400x300 (giữ tỉ lệ) rồi nén JPEG chất lượng 90%.
     * Ảnh gốc chụp điện thoại có thể vài MB, sau khi thu nhỏ còn khoảng vài chục KB nên gửi qua mạng nhanh.
     */
    public static byte[] createThumbnail(File file) throws IOException {
        BufferedImage src = ImageIO.read(file);
        if (src == null) {
            throw new IOException("File không phải định dạng ảnh hỗ trợ (jpg, png, gif, bmp)");
        }
        BufferedImage thumb = resizeToFit(src, DISPLAY_WIDTH * STORE_SCALE, DISPLAY_HEIGHT * STORE_SCALE);
        return writeJpeg(thumb);
    }

    /**
     * Đổi mảng byte ảnh thành ImageIcon để gắn lên JLabel. Trả về null nếu không có ảnh.
     * Ảnh lớn hơn khung hiển thị thì tạo ảnh "đa độ phân giải": màn hình 100% dùng bản vừa khung,
     * màn hình phóng to (150%...) Java tự chọn bản gốc nét hơn thay vì kéo giãn bản nhỏ.
     */
    public static ImageIcon toIcon(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            if (img == null) return null;
            if (img.getWidth() <= DISPLAY_WIDTH && img.getHeight() <= DISPLAY_HEIGHT) {
                return new ImageIcon(img); // ảnh nhỏ (lưu từ bản cũ) thì hiện nguyên
            }
            BufferedImage base = resizeToFit(img, DISPLAY_WIDTH, DISPLAY_HEIGHT);
            return new ImageIcon(new BaseMultiResolutionImage(base, img));
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Thu nhỏ ảnh vừa khung maxW x maxH, giữ tỉ lệ, không phóng to ảnh nhỏ.
     * Thu nhỏ từng bước một nửa cho tới gần kích thước đích: ảnh mịn hơn nhiều so với thu nhỏ 1 lần
     * (thu nhỏ 1 lần từ 4000px xuống 400px bỏ qua quá nhiều điểm ảnh nên bị răng cưa, nhòe).
     */
    private static BufferedImage resizeToFit(BufferedImage src, int maxW, int maxH) {
        double scale = Math.min(1.0, Math.min((double) maxW / src.getWidth(), (double) maxH / src.getHeight()));
        int targetW = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int targetH = Math.max(1, (int) Math.round(src.getHeight() * scale));

        BufferedImage current = drawScaled(src, src.getWidth(), src.getHeight()); // đổi về RGB nền trắng
        int w = current.getWidth();
        int h = current.getHeight();
        while (w / 2 >= targetW && h / 2 >= targetH) {
            w /= 2;
            h /= 2;
            current = drawScaled(current, w, h);
        }
        if (w != targetW || h != targetH) {
            current = drawScaled(current, targetW, targetH);
        }
        return current;
    }

    private static BufferedImage drawScaled(BufferedImage src, int w, int h) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(Color.WHITE); // ảnh PNG nền trong suốt -> tô nền trắng (JPEG không có kênh trong suốt)
        g.fillRect(0, 0, w, h);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    private static byte[] writeJpeg(BufferedImage img) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
