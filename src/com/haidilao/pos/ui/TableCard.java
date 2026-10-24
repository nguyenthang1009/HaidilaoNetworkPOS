package com.haidilao.pos.ui;

import com.haidilao.pos.model.DiningTable;

import javax.swing.*;
import java.awt.*;

/**
 * Thẻ một bàn trên sơ đồ của Lễ tân: tên bàn, số ghế, trạng thái (màu), khách / giờ vào, lượt đặt sắp tới, cảnh báo.
 * Dữ liệu lấy từ bản tin TABLE_INFO (đã giải mã).
 */
public class TableCard extends JPanel {
    private final String tableId;
    private final JLabel lblName = new JLabel();
    private final JLabel lblSeats = new JLabel();
    private final JLabel lblStatus = new JLabel();
    private final JLabel lblInfo = new JLabel();
    private final JLabel lblAlert = new JLabel();
    private boolean blinkOn;
    private final Timer blink;
    private String alert = "";

    public TableCard(String tableId) {
        super(new BorderLayout(0, 4));
        this.tableId = tableId;
        setBackground(Color.WHITE);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setPreferredSize(new Dimension(210, 118));

        lblName.setFont(new Font("Segoe UI", Font.BOLD, 16));
        lblName.setForeground(Theme.TEXT);
        lblSeats.setFont(Theme.FONT_SMALL);
        lblSeats.setForeground(Theme.MUTED);
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(lblName, BorderLayout.WEST);
        top.add(lblSeats, BorderLayout.EAST);

        lblStatus.setFont(Theme.FONT_BOLD);
        lblInfo.setFont(Theme.FONT_SMALL);
        lblInfo.setForeground(Theme.TEXT);
        JPanel mid = new JPanel(new GridLayout(2, 1, 0, 2));
        mid.setOpaque(false);
        mid.add(lblStatus);
        mid.add(lblInfo);

        lblAlert.setFont(new Font("Segoe UI", Font.BOLD, 12));
        lblAlert.setForeground(Theme.RED);

        add(top, BorderLayout.NORTH);
        add(mid, BorderLayout.CENTER);
        add(lblAlert, BorderLayout.SOUTH);

        // Viền nhấp nháy khi bàn có chuông / yêu cầu thanh toán / đơn mới chưa xử lý
        blink = new Timer(600, e -> {
            blinkOn = !blinkOn;
            applyBorder(currentColor);
        });
    }

    private Color currentColor = Theme.GREEN;

    public String getTableId() {
        return tableId;
    }

    /**
     * info: các trường TABLE_INFO đã giải mã
     * [1]mã [2]tên [3]số ghế [4]khu vực [5]trạng thái [6]mã phiên [7]khách [8]số khách [9]phục vụ [10]online [11]giờ vào [12]đặt sắp tới [13]yêu cầu TT
     */
    public void update(String[] info, String alertText) {
        String status = info[5];
        currentColor = Theme.tableColor(status);
        lblName.setText(info[2]);
        lblSeats.setText(info[3] + " ghế" + ("1".equals(info[10]) ? "" : " · Tablet offline"));
        lblStatus.setText("● " + status);
        lblStatus.setForeground(currentColor);

        String text;
        if (!"0".equals(info[6])) {
            String guests = "0".equals(info[8]) ? "Chưa ghi số khách" : info[8] + " khách";
            text = guests + (info[7].isEmpty() ? "" : " · " + info[7]) + (info[11].isEmpty() ? "" : " · vào " + info[11]);
            if (!info[9].isEmpty()) text += " · PV: " + info[9];
        } else if (info.length > 12 && !info[12].isEmpty()) {
            text = "Đặt " + info[12];
        } else {
            text = DiningTable.STATUS_CLEANING.equals(status) ? "Chờ dọn bàn" : "Sẵn sàng đón khách";
        }
        lblInfo.setText(text);
        setToolTipText("<html><b>" + info[2] + "</b> · " + info[3] + " ghế · " + info[4] + "<br>" + text + "</html>");

        alert = alertText == null ? "" : alertText;
        lblAlert.setText(alert.isEmpty() ? " " : alert);
        if (alert.isEmpty()) {
            blink.stop();
            blinkOn = false;
        } else if (!blink.isRunning()) {
            blink.start();
        }
        applyBorder(currentColor);
    }

    private void applyBorder(Color statusColor) {
        Color outline = (!alert.isEmpty() && blinkOn) ? Theme.RED : Theme.BORDER;
        int w = (!alert.isEmpty() && blinkOn) ? 2 : 1;
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(outline, w),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 6, 0, 0, statusColor),
                        BorderFactory.createEmptyBorder(8 - w + 1, 10, 6, 10))));
    }
}
