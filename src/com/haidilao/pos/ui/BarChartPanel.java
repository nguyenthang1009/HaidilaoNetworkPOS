package com.haidilao.pos.ui;

import com.haidilao.pos.model.StatRow;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Biểu đồ cột doanh thu theo ngày (vẽ bằng Graphics2D, không cần thư viện ngoài).
 */
public class BarChartPanel extends JPanel {
    private List<StatRow> rows = new ArrayList<>();

    public BarChartPanel() {
        setBackground(Color.WHITE);
        setPreferredSize(new Dimension(400, 220));
    }

    public void setData(List<StatRow> rows) {
        this.rows = new ArrayList<>(rows);
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        int left = 70, right = 16, top = 24, bottom = 42;
        g.setFont(Theme.FONT_SMALL);
        boolean allZero = rows.stream().allMatch(r -> r.getAmount() == 0);
        if (rows.isEmpty() || allZero) {
            g.setColor(Theme.MUTED);
            String s = "Chưa có doanh thu trong khoảng ngày đã chọn";
            g.drawString(s, (w - g.getFontMetrics().stringWidth(s)) / 2, h / 2);
            g.dispose();
            return;
        }
        long max = 1;
        for (StatRow r : rows) max = Math.max(max, r.getAmount());
        int plotW = w - left - right, plotH = h - top - bottom;

        // Lưới ngang + nhãn trục tiền
        for (int i = 0; i <= 4; i++) {
            int y = top + plotH - plotH * i / 4;
            g.setColor(new Color(236, 239, 243));
            g.drawLine(left, y, w - right, y);
            g.setColor(Theme.MUTED);
            String label = shortMoney(max * i / 4);
            g.drawString(label, left - 8 - g.getFontMetrics().stringWidth(label), y + 4);
        }

        int n = rows.size();
        double slot = (double) plotW / n;
        int barW = (int) Math.max(4, Math.min(48, slot * 0.6));
        int labelEvery = Math.max(1, (int) Math.ceil(n / (plotW / 60.0)));
        for (int i = 0; i < n; i++) {
            StatRow r = rows.get(i);
            int bh = (int) (plotH * (double) r.getAmount() / max);
            int x = left + (int) (slot * i + (slot - barW) / 2);
            int y = top + plotH - bh;
            g.setColor(Theme.PRIMARY);
            g.fillRoundRect(x, y, barW, bh, 4, 4);
            if (i % labelEvery == 0) {
                g.setColor(Theme.TEXT);
                String d = r.getLabel().length() >= 5 ? r.getLabel().substring(0, 5) : r.getLabel();
                g.drawString(d, x + barW / 2 - g.getFontMetrics().stringWidth(d) / 2, top + plotH + 16);
            }
            if (n <= 12 && bh > 0) {
                g.setColor(Theme.TEXT);
                String v = shortMoney(r.getAmount());
                g.drawString(v, x + barW / 2 - g.getFontMetrics().stringWidth(v) / 2, y - 4);
            }
        }
        g.setColor(Theme.TEXT);
        g.setFont(Theme.FONT_BOLD);
        g.drawString("Doanh thu theo ngày", left, 16);
        g.dispose();
    }

    private static String shortMoney(long v) {
        if (v >= 1_000_000) return String.format("%.1ftr", v / 1_000_000.0).replace(".0tr", "tr");
        if (v >= 1_000) return (v / 1_000) + "k";
        return String.valueOf(v);
    }
}
