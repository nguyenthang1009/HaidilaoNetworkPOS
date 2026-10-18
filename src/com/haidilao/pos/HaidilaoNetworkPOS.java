package com.haidilao.pos;

import com.haidilao.pos.protocol.ProtocolConstants;
import javax.swing.*;
import java.awt.*;

/**
 * Trình Khởi Chạy Trung Tâm Hệ Thống POS Haidilao (Main Launcher)
 * Hỗ trợ chạy linh hoạt trên 1 máy (Localhost) hoặc phân tán qua nhiều máy tính thật trong mạng LAN / Wi-Fi
 */
public class HaidilaoNetworkPOS {

    public static void main(String[] args) {
        try {
            for (javax.swing.UIManager.LookAndFeelInfo info : javax.swing.UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName()) || "Windows".equals(info.getName())) {
                    javax.swing.UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> showLauncherDialog());
    }

    private static void showLauncherDialog() {
        JFrame launcher = new JFrame("Hệ Thống Mạng POS Haidilao - Đồ Án Mạng Máy Tính");
        launcher.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        launcher.setSize(540, 480);
        launcher.setLocationRelativeTo(null);
        launcher.setResizable(false);

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBackground(new Color(24, 28, 36));
        root.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        // Header
        JLabel lblTitle = new JLabel("HAIDILAO NETWORK POS LAUNCHER", SwingConstants.CENTER);
        lblTitle.setFont(new Font("Segoe UI", Font.BOLD, 17));
        lblTitle.setForeground(new Color(255, 215, 0));

        JLabel lblSub = new JLabel("Đề tài: Lan Table Ordering & Service Bell (Instant Messaging & Firewall)", SwingConstants.CENTER);
        lblSub.setFont(new Font("Segoe UI", Font.ITALIC, 11));
        lblSub.setForeground(new Color(189, 195, 199));

        JPanel pnlTitle = new JPanel(new GridLayout(2, 1, 0, 4));
        pnlTitle.setOpaque(false);
        pnlTitle.add(lblTitle);
        pnlTitle.add(lblSub);

        // Khung cấu hình IP Server cho mạng LAN
        JPanel pnlIpConfig = new JPanel(new BorderLayout(8, 0));
        pnlIpConfig.setOpaque(false);
        pnlIpConfig.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(60, 70, 90)),
                " CẤU HÌNH IP SERVER (MẠNG LAN / WI-FI) ",
                0, 0, new Font("Segoe UI", Font.BOLD, 11), new Color(52, 152, 219)));

        JLabel lblIpPrompt = new JLabel("  Địa chỉ IP Máy Chủ POS:");
        lblIpPrompt.setForeground(new Color(236, 240, 241));
        lblIpPrompt.setFont(new Font("Segoe UI", Font.PLAIN, 12));

        JTextField txtServerIp = new JTextField(ProtocolConstants.DEFAULT_SERVER_HOST);
        txtServerIp.setFont(new Font("Segoe UI", Font.BOLD, 13));
        txtServerIp.setBackground(new Color(36, 42, 54));
        txtServerIp.setForeground(new Color(46, 204, 113));
        txtServerIp.setCaretColor(Color.WHITE);

        pnlIpConfig.add(lblIpPrompt, BorderLayout.WEST);
        pnlIpConfig.add(txtServerIp, BorderLayout.CENTER);

        JPanel pnlNorth = new JPanel(new BorderLayout(0, 10));
        pnlNorth.setOpaque(false);
        pnlNorth.add(pnlTitle, BorderLayout.NORTH);
        pnlNorth.add(pnlIpConfig, BorderLayout.SOUTH);
        root.add(pnlNorth, BorderLayout.NORTH);

        // Nút chọn phân hệ
        JPanel pnlButtons = new JPanel(new GridLayout(4, 1, 0, 10));
        pnlButtons.setOpaque(false);
        pnlButtons.setBorder(BorderFactory.createEmptyBorder(10, 0, 8, 0));

        JButton btnServer = createLauncherButton("1. [MÁY CHỦ] Khởi động POS Server & PC Admin (Port 8888)", new Color(192, 57, 43));
        btnServer.addActionListener(e -> new ServerAdminFrame().setVisible(true));

        JButton btnReception = createLauncherButton("2. [LỄ TÂN] Khởi động Quầy Lễ Tân (PC_LeTan - VLAN 10)", new Color(41, 128, 185));
        btnReception.addActionListener(e -> {
            String ip = txtServerIp.getText().trim();
            if (ip.isEmpty()) ip = ProtocolConstants.DEFAULT_SERVER_HOST;
            new ReceptionFrame(ip).setVisible(true);
        });

        JButton btnTablet = createLauncherButton("3. [TABLET] Khởi động Tablet Khách Hàng (Hỗ trợ Bàn 1..N)", new Color(39, 174, 96));
        btnTablet.addActionListener(e -> {
            String ip = txtServerIp.getText().trim();
            if (ip.isEmpty()) ip = ProtocolConstants.DEFAULT_SERVER_HOST;

            JComboBox<String> cboTable = new JComboBox<>(new String[]{
                    "Tablet_Ban1", "Tablet_Ban2", "Tablet_Ban3", "Tablet_Ban4", "Tablet_Ban5", "Tablet_Ban6"
            });
            cboTable.setEditable(true);

            JPanel promptPanel = new JPanel(new GridLayout(2, 1, 4, 4));
            promptPanel.add(new JLabel("Chọn hoặc gõ tên bàn (ví dụ Tablet_Ban4):"));
            promptPanel.add(cboTable);

            int opt = JOptionPane.showConfirmDialog(launcher, promptPanel, "Chọn Bàn Tablet",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

            if (opt == JOptionPane.OK_OPTION) {
                String picked = (cboTable.getSelectedItem() != null) ? cboTable.getSelectedItem().toString().trim() : "Tablet_Ban1";
                new TabletClientFrame(picked, ip).setVisible(true);
            }
        });

        JButton btnDemoAll = createLauncherButton("4. [CHẠY THỬ] KHỞI CHẠY TOÀN BỘ DEMO (1 Máy Localhost)", new Color(142, 68, 173));
        btnDemoAll.addActionListener(e -> {
            new ServerAdminFrame().setVisible(true);
            new ReceptionFrame("127.0.0.1").setVisible(true);
            new TabletClientFrame("Tablet_Ban1", "127.0.0.1").setVisible(true);
            new TabletClientFrame("Tablet_Ban2", "127.0.0.1").setVisible(true);
        });

        pnlButtons.add(btnServer);
        pnlButtons.add(btnReception);
        pnlButtons.add(btnTablet);
        pnlButtons.add(btnDemoAll);
        root.add(pnlButtons, BorderLayout.CENTER);

        JLabel lblFooter = new JLabel("Mô hình: VLAN 10 (192.168.10.0/24) | VLAN 20 Wi-Fi (192.168.20.0/24) & Dynamic LAN", SwingConstants.CENTER);
        lblFooter.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        lblFooter.setForeground(new Color(127, 140, 141));
        root.add(lblFooter, BorderLayout.SOUTH);

        launcher.setContentPane(root);
        launcher.setVisible(true);
    }

    private static JButton createLauncherButton(String text, Color bg) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("Segoe UI", Font.BOLD, 13));
        btn.setBackground(bg);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        return btn;
    }
}
