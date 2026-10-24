package com.haidilao.pos.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

/**
 * Cho một panel nằm trong JScrollPane (thẻ món, thẻ bàn, phiếu bếp) tự đổi số cột theo bề rộng khung cuộn:
 * màn hình rộng hiện nhiều cột, cửa sổ hẹp hiện ít cột, chỉ cuộn dọc. Nhờ vậy giao diện không bị vỡ
 * khi chạy trên các máy có độ phân giải khác nhau.
 */
public final class ResponsiveGrid {
    private static final String KEY = "responsiveGrid";

    private ResponsiveGrid() {}

    private static final class Spec {
        final JScrollPane scroll;
        final int minCellWidth;
        final int gap;

        Spec(JScrollPane scroll, int minCellWidth, int gap) {
            this.scroll = scroll;
            this.minCellWidth = minCellWidth;
            this.gap = gap;
        }
    }

    public static void install(JScrollPane scroll, JPanel grid, int minCellWidth, int gap) {
        grid.setLayout(new GridLayout(0, 1, gap, gap));
        grid.putClientProperty(KEY, new Spec(scroll, minCellWidth, gap));
        // Bọc lưới vào BorderLayout.NORTH: lưới luôn rộng bằng khung cuộn nhưng giữ đúng chiều cao các thẻ
        // (không bị kéo giãn cho đầy khung khi có ít thẻ)
        JPanel holder = new JPanel(new BorderLayout());
        holder.setBackground(grid.getBackground());
        holder.add(grid, BorderLayout.NORTH);
        scroll.setViewportView(holder);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.getViewport().setBackground(grid.getBackground());
        scroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                refresh(grid);
            }
        });
        SwingUtilities.invokeLater(() -> refresh(grid));
    }

    /** Tính lại số cột theo bề rộng hiện tại (gọi sau khi thêm / xóa thẻ) */
    public static void refresh(JPanel grid) {
        Object o = grid.getClientProperty(KEY);
        if (!(o instanceof Spec)) return;
        Spec spec = (Spec) o;
        int width = spec.scroll.getViewport().getWidth();
        if (width <= 0) width = spec.scroll.getWidth();
        if (width <= 0) width = spec.minCellWidth * 2;
        Insets in = grid.getInsets();
        int inner = width - in.left - in.right;
        int cols = Math.max(1, (inner + spec.gap) / (spec.minCellWidth + spec.gap));
        GridLayout gl = (GridLayout) grid.getLayout();
        gl.setRows(0);
        gl.setColumns(cols);
        grid.revalidate();
        grid.repaint();
    }
}
