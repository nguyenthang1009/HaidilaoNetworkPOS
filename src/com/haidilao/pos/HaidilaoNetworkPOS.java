package com.haidilao.pos;

import com.haidilao.pos.ui.Theme;

import javax.swing.*;
import java.awt.*;

/**
 * Trình khởi chạy: chọn vai trò của máy này (Máy chủ / Lễ tân / Bếp / Phục vụ / Tablet).
 * Không cần nhập IP: các máy Lễ tân, Bếp, Tablet tự tìm máy chủ trong cùng mạng Wi-Fi (UDP broadcast).
 *
 * Có thể mở thẳng một vai trò bằng tham số: java -jar HaidilaoNetworkPOS.jar server | reception | kitchen | waiter | tablet [Tablet_Ban1]
 */
public class HaidilaoNetworkPOS {

    public static void main(String[] args) {
        applyLookAndFeel();
        SwingUtilities.invokeLater(() -> {
            if (args.length > 0) {
                launchRole(args[0].toLowerCase(), args.length > 1 ? args[1] : null);
            } else {
                showLauncher();
            }
        });
    }

    /** Giao diện Nimbus, font Segoe UI, màu chọn theo bộ màu chung */
    public static void applyLookAndFeel() {
        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (Exception ignored) {}
        UIManager.put("nimbusBase", new Color(60, 66, 80));
        UIManager.put("nimbusSelectionBackground", Theme.PRIMARY);
        UIManager.put("defaultFont", Theme.FONT);
        UIManager.put("Table.alternateRowColor", new Color(248, 249, 251));
    }

    private static void launchRole(String role, String extra) {
        switch (role) {
            case "server":
                ServerAdminFrame.startWithLogin();
                break;
            case "reception":
                new ReceptionFrame().startWithLogin();
                break;
            case "kitchen":
                new KitchenFrame().startWithLogin();
                break;
            case "waiter":
                new WaiterFrame().startWithLogin();
                break;
            case "tablet":
                TabletClientFrame.launch(extra);
                break;
            default:
                showLauncher();
        }
    }

    private static void showLauncher() {
        JFrame launcher = new JFrame("LAN Table Ordering & Service Bell");
        launcher.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBackground(Theme.DARK);
        root.setBorder(BorderFactory.createEmptyBorder(22, 26, 18, 26));

        JLabel title = new JLabel("LAN TABLE ORDERING & SERVICE BELL", SwingConstants.CENTER);
        title.setFont(new Font("Segoe UI", Font.BOLD, 20));
        title.setForeground(Theme.GOLD);
        JLabel sub = new JLabel("Chọn vai trò của máy này. Các máy trong cùng mạng Wi-Fi tự tìm thấy máy chủ.", SwingConstants.CENTER);
        sub.setFont(Theme.FONT);
        sub.setForeground(new Color(200, 206, 216));
        JPanel head = new JPanel(new GridLayout(2, 1, 0, 6));
        head.setOpaque(false);
        head.add(title);
        head.add(sub);
        root.add(head, BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridLayout(2, 3, 14, 14));
        grid.setOpaque(false);
        grid.add(roleButton("MÁY CHỦ", "Quản lý đăng nhập · thực đơn, bàn, nhân viên, thống kê", Theme.PRIMARY, () -> {
            launcher.dispose();
            ServerAdminFrame.startWithLogin();
        }));
        grid.add(roleButton("QUẦY LỄ TÂN", "Sơ đồ bàn, đặt bàn, đơn hàng, thanh toán, tin nhắn", Theme.BLUE, () -> {
            launcher.dispose();
            new ReceptionFrame().startWithLogin();
        }));
        grid.add(roleButton("MÀN HÌNH BẾP", "Nhận đơn kèm ghi chú, báo đang nấu / đã xong", Theme.ORANGE, () -> {
            launcher.dispose();
            new KitchenFrame().startWithLogin();
        }));
        grid.add(roleButton("NHÂN VIÊN PHỤC VỤ", "Bàn được giao, nhắn tin với khách, mang món ra", Theme.PURPLE, () -> {
            launcher.dispose();
            new WaiterFrame().startWithLogin();
        }));
        grid.add(roleButton("TABLET TẠI BÀN", "Khách gọi món, gọi phục vụ, nhắn tin, thanh toán", Theme.GREEN, () -> {
            String table = new TabletSetupDialog(launcher).showDialog();
            if (table != null) {
                launcher.dispose();
                TabletClientFrame.launch(table);
            }
        }));
        root.add(grid, BorderLayout.CENTER);

        JButton demo = new JButton("Chạy thử tất cả trên máy này (máy chủ + Lễ tân + Bếp + Tablet bàn 1)");
        demo.setFont(Theme.FONT);
        demo.setFocusable(false);
        demo.addActionListener(e -> {
            launcher.dispose();
            ServerAdminFrame.startWithLogin();
            new ReceptionFrame().startWithLogin();
            new KitchenFrame().startWithLogin();
            TabletClientFrame.launch("Tablet_Ban1");
        });
        JLabel foot = new JLabel("Client - Server TCP 8888 · tự tìm máy chủ qua UDP 8889 · mã hóa AES-128 · phân quyền theo vai trò",
                SwingConstants.CENTER);
        foot.setFont(Theme.FONT_SMALL);
        foot.setForeground(new Color(150, 160, 175));
        JPanel south = new JPanel(new BorderLayout(0, 8));
        south.setOpaque(false);
        south.add(demo, BorderLayout.NORTH);
        south.add(foot, BorderLayout.SOUTH);
        root.add(south, BorderLayout.SOUTH);

        launcher.setContentPane(root);
        launcher.setSize(900, 480);
        launcher.setMinimumSize(new Dimension(760, 420));
        launcher.setLocationRelativeTo(null);
        launcher.setVisible(true);
    }

    private static JButton roleButton(String title, String desc, Color color, Runnable action) {
        JButton b = new JButton("<html><div style='text-align:center'><span style='font-size:15px'><b>" + title + "</b></span><br><br>"
                + "<span style='font-size:10px'>" + desc + "</span></div></html>");
        b.setBackground(color);
        b.setForeground(Color.WHITE);
        b.setFocusable(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addActionListener(e -> action.run());
        return b;
    }
}
