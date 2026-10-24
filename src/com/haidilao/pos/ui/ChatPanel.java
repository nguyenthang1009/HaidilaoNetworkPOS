package com.haidilao.pos.ui;

import javax.swing.*;
import java.awt.*;

/**
 * Khung chat dạng bong bóng như ứng dụng nhắn tin: tin của mình nằm bên phải (nền đỏ),
 * tin của bên kia nằm bên trái (nền trắng), dòng thông báo hệ thống nằm giữa (chữ xám).
 * Được gắn vào một JPanel giữ chỗ trên form lúc chạy chương trình.
 */
public class ChatPanel extends JPanel {
    private final JPanel list = new JPanel();
    private final JScrollPane scroll;
    private final JLabel empty = new JLabel("Chưa có tin nhắn", SwingConstants.CENTER);

    public ChatPanel() {
        super(new BorderLayout());
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBackground(new Color(236, 239, 243));
        list.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        // Bọc trong panel BorderLayout.NORTH để các bong bóng dồn lên trên, không bị kéo giãn
        JPanel holder = new JPanel(new BorderLayout());
        holder.setBackground(list.getBackground());
        holder.add(list, BorderLayout.NORTH);
        scroll = new JScrollPane(holder);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(scroll, BorderLayout.CENTER);
        empty.setForeground(Theme.MUTED);
        empty.setFont(Theme.FONT_SMALL);
        clear();
    }

    public void clear() {
        list.removeAll();
        empty.setAlignmentX(Component.CENTER_ALIGNMENT);
        list.add(empty);
        refresh();
    }

    /** Thêm một tin nhắn. mine = true: tin do máy này gửi (bên phải) */
    public void addMessage(String sender, String text, String time, boolean mine) {
        list.remove(empty);
        JPanel row = new JPanel(new FlowLayout(mine ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 3));
        row.setOpaque(false);
        String color = mine ? "#ffffff" : "#2c3e50";
        String meta = (sender == null || sender.isEmpty() ? "" : escape(sender) + " · ") + escape(time);
        JLabel bubble = new JLabel("<html><div style='width:" + bubbleWidth() + "px'>"
                + "<span style='font-size:8px;color:" + (mine ? "#f5d6d6" : "#7f8c8d") + "'>" + meta + "</span><br>"
                + "<span style='color:" + color + "'>" + escape(text).replace("\n", "<br>") + "</span></div></html>");
        bubble.setFont(Theme.FONT);
        bubble.setOpaque(true);
        bubble.setBackground(mine ? Theme.PRIMARY : Color.WHITE);
        bubble.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(mine ? Theme.PRIMARY : Theme.BORDER),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        row.add(bubble);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        list.add(row);
        refresh();
    }

    /** Dòng thông báo nhỏ ở giữa (vd. "Khách bấm chuông: châm nước lẩu") */
    public void addNotice(String text) {
        list.remove(empty);
        JLabel l = new JLabel(text, SwingConstants.CENTER);
        l.setFont(Theme.FONT_SMALL);
        l.setForeground(Theme.MUTED);
        l.setAlignmentX(Component.CENTER_ALIGNMENT);
        l.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        row.setOpaque(false);
        row.add(l);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        list.add(row);
        refresh();
    }

    /** Bề rộng bong bóng theo khung chat hiện tại (khung đang ẩn trong tab khác thì lấy bề rộng của panel) */
    private int bubbleWidth() {
        int w = scroll.getViewport().getWidth();
        if (w < 60) w = getWidth();
        if (w < 60) w = 340;
        return Math.max(120, Math.min(340, (int) (w * 0.62)));
    }

    private void refresh() {
        list.revalidate();
        list.repaint();
        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = scroll.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Gắn khung chat vào panel giữ chỗ trên form (panel đó dùng BorderLayout) */
    public static ChatPanel installInto(JPanel host) {
        ChatPanel chat = new ChatPanel();
        host.setLayout(new BorderLayout());
        host.add(chat, BorderLayout.CENTER);
        host.revalidate();
        return chat;
    }
}
