package com.haidilao.pos.ui;

import com.haidilao.pos.model.DiningTable;
import com.haidilao.pos.model.Order;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.text.DecimalFormat;

/**
 * Bộ màu, font và vài hàm định dạng dùng chung cho mọi màn hình, để các chương trình nhìn đồng bộ như một ứng dụng.
 */
public final class Theme {
    private Theme() {}

    public static final Color PRIMARY = new Color(169, 29, 34);      // đỏ thương hiệu
    public static final Color PRIMARY_DARK = new Color(120, 18, 22);
    public static final Color DARK = new Color(31, 36, 48);          // thanh tiêu đề
    public static final Color BG = new Color(244, 245, 247);         // nền
    public static final Color CARD = Color.WHITE;
    public static final Color BORDER = new Color(222, 226, 232);
    public static final Color TEXT = new Color(44, 62, 80);
    public static final Color MUTED = new Color(127, 140, 141);
    public static final Color GOLD = new Color(241, 196, 15);
    public static final Color GREEN = new Color(39, 174, 96);
    public static final Color BLUE = new Color(41, 128, 185);
    public static final Color ORANGE = new Color(230, 126, 34);
    public static final Color PURPLE = new Color(142, 68, 173);
    public static final Color GRAY = new Color(149, 165, 166);
    public static final Color RED = new Color(231, 76, 60);

    public static final Font FONT = new Font("Segoe UI", Font.PLAIN, 13);
    public static final Font FONT_BOLD = new Font("Segoe UI", Font.BOLD, 13);
    public static final Font FONT_SMALL = new Font("Segoe UI", Font.PLAIN, 11);
    public static final Font FONT_TITLE = new Font("Segoe UI", Font.BOLD, 16);

    private static final DecimalFormat MONEY = new DecimalFormat("#,###");

    /** 125000 -> "125.000 đ" (dấu chấm ngăn cách hàng nghìn theo kiểu Việt Nam) */
    public static String money(long v) {
        return MONEY.format(v).replace(',', '.') + " đ";
    }

    /** Màu theo trạng thái bàn */
    public static Color tableColor(String status) {
        if (DiningTable.STATUS_SERVING.equals(status)) return BLUE;
        if (DiningTable.STATUS_RESERVED.equals(status)) return PURPLE;
        if (DiningTable.STATUS_CLEANING.equals(status)) return GRAY;
        return GREEN;
    }

    /** Màu theo trạng thái đơn */
    public static Color orderColor(String status) {
        if (Order.STATUS_WAITING.equals(status)) return ORANGE;
        if (Order.STATUS_COOKING.equals(status)) return BLUE;
        if (Order.STATUS_DONE.equals(status)) return GREEN;
        if (Order.STATUS_CANCELLED.equals(status)) return GRAY;
        return TEXT;
    }

    /** Bảng dữ liệu: dòng cao hơn, tiêu đề đậm, tô màu cột trạng thái (statusColumn = -1 nếu không có) */
    public static void styleTable(JTable table, int statusColumn) {
        table.setRowHeight(Math.max(table.getRowHeight(), 28));
        table.setFont(FONT);
        table.setShowVerticalLines(false);
        table.setGridColor(BORDER);
        table.setSelectionBackground(new Color(253, 235, 208));
        table.setSelectionForeground(TEXT);
        table.setFillsViewportHeight(true);
        JTableHeader h = table.getTableHeader();
        h.setFont(FONT_BOLD);
        h.setReorderingAllowed(false);
        if (statusColumn >= 0 && statusColumn < table.getColumnCount()) {
            table.getColumnModel().getColumn(statusColumn).setCellRenderer(new DefaultTableCellRenderer() {
                @Override
                public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
                    Component c = super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                    String s = v == null ? "" : v.toString();
                    Color color = s.startsWith("TRỐNG") || s.startsWith("ĐANG PHỤC VỤ") || s.startsWith("ĐÃ ĐẶT") || s.startsWith("CẦN DỌN")
                            ? tableColor(s) : orderColor(s);
                    c.setForeground(color);
                    c.setFont(FONT_BOLD);
                    return c;
                }
            });
        }
    }

    /** Hiện một thông báo ngắn trên nhãn rồi tự xóa sau vài giây (thay cho việc dồn mọi thứ vào khung chat) */
    public static void flash(JLabel label, String text, int millis) {
        label.setText(text);
        Object old = label.getClientProperty("flashTimer");
        if (old instanceof Timer) ((Timer) old).stop();
        Timer t = new Timer(millis, e -> label.setText(" "));
        t.setRepeats(false);
        label.putClientProperty("flashTimer", t);
        t.start();
    }

    /** Cửa sổ chiếm phần lớn màn hình hiện tại (vừa với laptop lẫn màn hình lớn) */
    public static void fitToScreen(Window w, double ratio) {
        Rectangle r = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        w.setSize((int) (r.width * ratio), (int) (r.height * ratio));
        w.setLocationRelativeTo(null);
    }
}
