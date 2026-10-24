/*
 * KitchenFrame.java
 */
package com.haidilao.pos;

import com.haidilao.pos.client.ClientEventListener;
import com.haidilao.pos.client.PosClient;
import com.haidilao.pos.model.Order;
import com.haidilao.pos.protocol.NetworkPacket;
import com.haidilao.pos.protocol.ProtocolConstants;
import com.haidilao.pos.ui.ResponsiveGrid;
import com.haidilao.pos.ui.Theme;
import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 * Màn hình Bếp (KDS): phiếu đơn dạng lưới, mỗi phiếu gồm STT, bàn, giờ gửi, số phút đã chờ, từng món kèm ghi chú;
 * nút Bắt đầu nấu / Xong gửi KITCHEN_STATUS lên Server.
 */
public class KitchenFrame extends javax.swing.JFrame implements ClientEventListener {

    private static final int LATE_MINUTES = 15;
    private PosClient client;
    private LoginDialog pendingLogin;
    private boolean loading = false;
    private boolean pendingOnly = true;
    // Các phiếu đang hiện, theo mã đơn (thứ tự đơn cũ trước)
    private final Map<Integer, Ticket> tickets = new LinkedHashMap<>();

    /** Một phiếu bếp: dữ liệu của bản tin KITCHEN_ORDER */
    private static final class Ticket {
        int orderId;
        int orderNo;
        String tableName;
        String time;
        String status;
        String note;
        List<String[]> lines;
    }

    public KitchenFrame() {
        initComponents();
        initCustomLogic();
    }

    private void initCustomLogic() {
        ResponsiveGrid.install(scrollTickets, pnlTickets, 320, 12);
        new Timer(1000, e -> lblClock.setText(new SimpleDateFormat("HH:mm:ss").format(new Date()))).start();
        new Timer(30_000, e -> rebuild()).start(); // cập nhật số phút chờ
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (client != null) client.disconnect();
            }
        });
        setMinimumSize(new Dimension(900, 600));
        Theme.fitToScreen(this, 0.94);
    }

    /** Đăng nhập (tài khoản Bếp hoặc Quản lý), tự tìm máy chủ trong mạng; đăng nhập xong mới hiện màn hình */
    public void startWithLogin() {
        LoginDialog dlg = new LoginDialog(null, "Đăng nhập Màn hình Bếp", (user, pass, d) -> {
            pendingLogin = d;
            if (client != null) client.disconnect();
            client = new PosClient(null, ProtocolConstants.SERVER_PORT, "KITCHEN", buildKitchenId(), ProtocolConstants.KEY_KITCHEN, user, pass);
            client.setListener(this);
            d.showBusy("Đang tìm máy chủ trong mạng Wi-Fi...");
            client.connect();
        });
        if (dlg.showDialog()) {
            setVisible(true);
        } else {
            if (client != null) client.disconnect();
            dispose();
        }
    }

    private static String buildKitchenId() {
        String host = "";
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {}
        host = host.replaceAll("[^A-Za-z0-9_-]", "").toUpperCase();
        String id = host.isEmpty() ? "Bep" : "Bep_" + host;
        return id.length() > 30 ? id.substring(0, 30) : id;
    }

    private void requestOrders() {
        if (client != null && client.isConnected()) client.send(NetworkPacket.buildGetKitchenOrders(pendingOnly));
    }

    private void toast(String text) {
        Theme.flash(lblToast, text, 9000);
    }

    /** Số phút từ giờ gửi đơn (HH:mm) tới bây giờ */
    private static int minutesSince(String hhmm) {
        try {
            String[] p = hhmm.split(":");
            Calendar now = Calendar.getInstance();
            int diff = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE) - (Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]));
            return diff < 0 ? diff + 24 * 60 : diff;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static boolean isOpenStatus(String s) {
        return Order.STATUS_WAITING.equals(s) || Order.STATUS_COOKING.equals(s);
    }

    /** Vẽ lại toàn bộ phiếu + bộ đếm trên thanh tiêu đề */
    private void rebuild() {
        pnlTickets.removeAll();
        int waiting = 0, cooking = 0, late = 0;
        for (Ticket t : tickets.values()) {
            if (pendingOnly && !isOpenStatus(t.status)) continue;
            if (Order.STATUS_WAITING.equals(t.status)) waiting++;
            if (Order.STATUS_COOKING.equals(t.status)) cooking++;
            if (isOpenStatus(t.status) && minutesSince(t.time) >= LATE_MINUTES) late++;
            pnlTickets.add(createTicket(t));
        }
        lblCountWaiting.setText("Chờ bếp: " + waiting);
        lblCountCooking.setText("Đang nấu: " + cooking);
        lblCountLate.setText("Chờ quá " + LATE_MINUTES + " phút: " + late);
        lblBoardHint.setText(pnlTickets.getComponentCount() == 0
                ? (pendingOnly ? "Không còn đơn nào cần nấu. Đơn mới sẽ tự hiện ở đây." : "Hôm nay chưa có đơn nào.")
                : "Phiếu cũ nhất ở đầu. Bấm \"Bắt đầu nấu\" khi nhận làm, \"Xong\" khi món đã sẵn sàng để Lễ tân cho mang ra.");
        ResponsiveGrid.refresh(pnlTickets);
    }

    private JPanel createTicket(Ticket t) {
        int minutes = minutesSince(t.time);
        boolean late = isOpenStatus(t.status) && minutes >= LATE_MINUTES;
        Color color = late ? Theme.RED : Theme.orderColor(t.status);

        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createLineBorder(late ? Theme.RED : Theme.BORDER, late ? 2 : 1));

        JPanel head = new JPanel(new BorderLayout(8, 0));
        head.setBackground(color);
        head.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        JLabel no = new JLabel(Order.formatOrderNo(t.orderNo) + "  ·  " + t.tableName);
        no.setFont(new Font("Segoe UI", Font.BOLD, 17));
        no.setForeground(Color.WHITE);
        JLabel when = new JLabel(t.time + (isOpenStatus(t.status) ? "  ·  " + minutes + " phút" : "  ·  " + t.status));
        when.setFont(Theme.FONT_BOLD);
        when.setForeground(Color.WHITE);
        head.add(no, BorderLayout.WEST);
        head.add(when, BorderLayout.EAST);

        StringBuilder html = new StringBuilder("<html><div style='width:230px'>");
        for (String[] l : t.lines) {
            html.append("<div style='margin-bottom:5px'><b style='font-size:13px'>").append(l[1]).append(" × </b>")
                .append(escape(l[0]));
            if (!l[2].isEmpty()) html.append("<br><i style='color:#c0392b'>Ghi chú: ").append(escape(l[2])).append("</i>");
            html.append("</div>");
        }
        if (!t.note.isEmpty()) {
            html.append("<div style='margin-top:4px;padding:4px;background:#fdebd0'><b>Ghi chú đơn:</b> ").append(escape(t.note)).append("</div>");
        }
        html.append("</div></html>");
        JLabel body = new JLabel(html.toString());
        body.setFont(Theme.FONT);
        body.setVerticalAlignment(SwingConstants.TOP);
        body.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        JPanel actions = new JPanel(new GridLayout(1, 0, 8, 0));
        actions.setOpaque(false);
        actions.setBorder(BorderFactory.createEmptyBorder(0, 12, 10, 12));
        if (Order.STATUS_WAITING.equals(t.status)) {
            actions.add(actionButton("Bắt đầu nấu", Theme.BLUE, () -> changeStatus(t, Order.STATUS_COOKING)));
        }
        if (isOpenStatus(t.status)) {
            actions.add(actionButton("Xong", Theme.GREEN, () -> changeStatus(t, Order.STATUS_DONE)));
        } else {
            JLabel s = new JLabel(t.status, SwingConstants.CENTER);
            s.setFont(Theme.FONT_BOLD);
            s.setForeground(Theme.orderColor(t.status));
            actions.add(s);
        }

        card.add(head, BorderLayout.NORTH);
        card.add(body, BorderLayout.CENTER);
        card.add(actions, BorderLayout.SOUTH);
        return card;
    }

    private static JButton actionButton(String text, Color bg, Runnable r) {
        JButton b = new JButton(text);
        b.setFont(new Font("Segoe UI", Font.BOLD, 14));
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setFocusable(false);
        b.addActionListener(e -> r.run());
        return b;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void changeStatus(Ticket t, String status) {
        if (client == null || !client.isConnected()) {
            toast("Mất kết nối máy chủ, chưa cập nhật được.");
            return;
        }
        client.send(NetworkPacket.buildKitchenStatus(t.orderId, status));
    }

    /** KITCHEN_ORDER|MÃ_ĐƠN|STT|TÊN_BÀN|GIỜ|TRẠNG_THÁI|GHI_CHÚ_ĐƠN|CÁC_MÓN */
    private Ticket parseTicket(String[] f) {
        Ticket t = new Ticket();
        t.orderId = NetworkPacket.parseInt(f[1], 0);
        t.orderNo = NetworkPacket.parseInt(f[2], 0);
        t.tableName = NetworkPacket.dec(f[3]);
        t.time = f[4];
        t.status = NetworkPacket.dec(f[5]);
        t.note = NetworkPacket.dec(f[6]);
        t.lines = NetworkPacket.decodeKitchenLines(f.length > 7 ? f[7] : "");
        return t;
    }

    // ------------------------------------------------------------------ ClientEventListener

    @Override
    public void onConnected() {
        SwingUtilities.invokeLater(() -> {
            if (pendingLogin != null) {
                pendingLogin.loginSucceeded();
                pendingLogin = null;
            }
            lblConnection.setText("● Đã kết nối máy chủ " + client.getHost());
            lblConnection.setForeground(new Color(130, 230, 160));
            lblStaff.setText("Nhân viên: " + client.getStaffName());
            setTitle("Màn hình Bếp - " + client.getStaffName());
        });
        requestOrders();
    }

    @Override
    public void onDisconnected(String reason) {
        SwingUtilities.invokeLater(() -> {
            if (pendingLogin != null) {
                pendingLogin.showBusy("Đang tìm máy chủ... (" + reason + ")");
                return;
            }
            lblConnection.setText("● Mất kết nối, đang tìm lại máy chủ...");
            lblConnection.setForeground(Theme.GOLD);
        });
    }

    @Override
    public void onAuthFailed(String reason) {
        SwingUtilities.invokeLater(() -> {
            if (pendingLogin != null) pendingLogin.showError(reason);
            else JOptionPane.showMessageDialog(this, reason, "Không thể kết nối", JOptionPane.WARNING_MESSAGE);
        });
    }

    @Override
    public void onKicked(String reason) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, reason, "Thiết bị đã bị khóa", JOptionPane.ERROR_MESSAGE);
            System.exit(0);
        });
    }

    @Override
    public void onMessageReceived(String raw) {
        SwingUtilities.invokeLater(() -> {
            String[] f = NetworkPacket.split(raw);
            switch (f[0]) {
                case "KITCHEN_BEGIN":
                    loading = true;
                    tickets.clear();
                    break;
                case "KITCHEN_ORDER": {
                    if (f.length < 7) return;
                    Ticket t = parseTicket(f);
                    boolean isNew = !tickets.containsKey(t.orderId);
                    tickets.put(t.orderId, t);
                    if (!loading) {
                        rebuild();
                        if (isNew) {
                            Toolkit.getDefaultToolkit().beep();
                            toast("Đơn mới " + Order.formatOrderNo(t.orderNo) + " của " + t.tableName
                                    + (t.note.isEmpty() ? "" : " · ghi chú: " + t.note));
                        }
                    }
                    break;
                }
                case "KITCHEN_END":
                    loading = false;
                    rebuild();
                    break;
                case "ORDER_UPDATED": {
                    // ORDER_UPDATED|MÃ_ĐƠN|TRẠNG_THÁI|STT|BÀN
                    Ticket t = tickets.get(NetworkPacket.parseInt(f[1], 0));
                    if (t != null) {
                        t.status = f[2];
                        if (Order.STATUS_CANCELLED.equals(f[2])) {
                            Toolkit.getDefaultToolkit().beep();
                            toast("Lễ tân đã hủy đơn " + Order.formatOrderNo(t.orderNo) + " của " + t.tableName + ", ngừng nấu đơn này.");
                        }
                        rebuild();
                    }
                    break;
                }
                case "SERVER_ACK":
                    toast(f.length > 1 ? f[1] : "");
                    break;
                default:
                    break;
            }
        });
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        pnlHeader = new javax.swing.JPanel();
        pnlHeaderLeft = new javax.swing.JPanel();
        lblTitle = new javax.swing.JLabel();
        lblStaff = new javax.swing.JLabel();
        pnlCounters = new javax.swing.JPanel();
        lblCountWaiting = new javax.swing.JLabel();
        lblCountCooking = new javax.swing.JLabel();
        lblCountLate = new javax.swing.JLabel();
        pnlHeaderRight = new javax.swing.JPanel();
        cboFilter = new javax.swing.JComboBox<>();
        btnRefresh = new javax.swing.JButton();
        pnlClock = new javax.swing.JPanel();
        lblConnection = new javax.swing.JLabel();
        lblClock = new javax.swing.JLabel();
        pnlBoard = new javax.swing.JPanel();
        lblBoardHint = new javax.swing.JLabel();
        scrollTickets = new javax.swing.JScrollPane();
        pnlTickets = new javax.swing.JPanel();
        pnlStatusBar = new javax.swing.JPanel();
        lblToast = new javax.swing.JLabel();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("Màn hình Bếp");

        pnlHeader.setBackground(new java.awt.Color(31, 36, 48));
        pnlHeader.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 16, 10, 16));
        pnlHeader.setLayout(new java.awt.BorderLayout(16, 0));

        pnlHeaderLeft.setOpaque(false);
        pnlHeaderLeft.setLayout(new java.awt.GridLayout(2, 1));

        lblTitle.setFont(new java.awt.Font("Segoe UI", 1, 18)); // NOI18N
        lblTitle.setForeground(new java.awt.Color(241, 196, 15));
        lblTitle.setText("MÀN HÌNH BẾP");
        pnlHeaderLeft.add(lblTitle);

        lblStaff.setForeground(new java.awt.Color(200, 206, 216));
        lblStaff.setText("Nhân viên: -");
        pnlHeaderLeft.add(lblStaff);

        pnlHeader.add(pnlHeaderLeft, java.awt.BorderLayout.WEST);

        pnlCounters.setOpaque(false);
        pnlCounters.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 6));

        lblCountWaiting.setBackground(new java.awt.Color(44, 52, 68));
        lblCountWaiting.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblCountWaiting.setForeground(new java.awt.Color(248, 196, 113));
        lblCountWaiting.setText("Chờ bếp: 0");
        lblCountWaiting.setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 12, 5, 12));
        lblCountWaiting.setOpaque(true);
        pnlCounters.add(lblCountWaiting);

        lblCountCooking.setBackground(new java.awt.Color(44, 52, 68));
        lblCountCooking.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblCountCooking.setForeground(new java.awt.Color(133, 193, 233));
        lblCountCooking.setText("Đang nấu: 0");
        lblCountCooking.setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 12, 5, 12));
        lblCountCooking.setOpaque(true);
        pnlCounters.add(lblCountCooking);

        lblCountLate.setBackground(new java.awt.Color(44, 52, 68));
        lblCountLate.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblCountLate.setForeground(new java.awt.Color(241, 148, 138));
        lblCountLate.setText("Chờ quá 15 phút: 0");
        lblCountLate.setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 12, 5, 12));
        lblCountLate.setOpaque(true);
        pnlCounters.add(lblCountLate);

        pnlHeader.add(pnlCounters, java.awt.BorderLayout.CENTER);

        pnlHeaderRight.setOpaque(false);
        pnlHeaderRight.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 4));

        cboFilter.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        cboFilter.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "Đơn chưa xong", "Tất cả đơn hôm nay" }));
        cboFilter.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cboFilterActionPerformed(evt);
            }
        });
        pnlHeaderRight.add(cboFilter);

        btnRefresh.setBackground(new java.awt.Color(236, 240, 241));
        btnRefresh.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnRefresh.setForeground(new java.awt.Color(44, 62, 80));
        btnRefresh.setText("Làm mới");
        btnRefresh.setFocusable(false);
        btnRefresh.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnRefreshActionPerformed(evt);
            }
        });
        pnlHeaderRight.add(btnRefresh);

        pnlClock.setOpaque(false);
        pnlClock.setLayout(new java.awt.GridLayout(2, 1));

        lblConnection.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        lblConnection.setForeground(new java.awt.Color(241, 196, 15));
        lblConnection.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        lblConnection.setText("● Đang tìm máy chủ...");
        pnlClock.add(lblConnection);

        lblClock.setForeground(new java.awt.Color(200, 206, 216));
        lblClock.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        lblClock.setText("--:--");
        pnlClock.add(lblClock);

        pnlHeaderRight.add(pnlClock);

        pnlHeader.add(pnlHeaderRight, java.awt.BorderLayout.EAST);

        getContentPane().add(pnlHeader, java.awt.BorderLayout.NORTH);

        pnlBoard.setBackground(new java.awt.Color(233, 236, 241));
        pnlBoard.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 12, 10, 12));
        pnlBoard.setLayout(new java.awt.BorderLayout(0, 8));

        lblBoardHint.setForeground(new java.awt.Color(127, 140, 141));
        lblBoardHint.setText("Phiếu cũ nhất ở đầu. Bấm \"Bắt đầu nấu\" khi nhận làm, \"Xong\" khi món đã sẵn sàng để Lễ tân cho mang ra.");
        pnlBoard.add(lblBoardHint, java.awt.BorderLayout.NORTH);

        pnlTickets.setBackground(new java.awt.Color(233, 236, 241));
        pnlTickets.setLayout(new java.awt.GridLayout(0, 3, 12, 12));
        scrollTickets.setViewportView(pnlTickets);

        pnlBoard.add(scrollTickets, java.awt.BorderLayout.CENTER);

        getContentPane().add(pnlBoard, java.awt.BorderLayout.CENTER);

        pnlStatusBar.setBackground(new java.awt.Color(255, 255, 255));
        pnlStatusBar.setBorder(javax.swing.BorderFactory.createCompoundBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(222, 226, 232)), javax.swing.BorderFactory.createEmptyBorder(6, 14, 6, 14)));
        pnlStatusBar.setLayout(new java.awt.BorderLayout(10, 0));

        lblToast.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        lblToast.setForeground(new java.awt.Color(44, 62, 80));
        lblToast.setText(" ");
        pnlStatusBar.add(lblToast, java.awt.BorderLayout.CENTER);

        getContentPane().add(pnlStatusBar, java.awt.BorderLayout.SOUTH);

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void cboFilterActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cboFilterActionPerformed
        pendingOnly = cboFilter.getSelectedIndex() == 0;
        requestOrders();
    }//GEN-LAST:event_cboFilterActionPerformed

    private void btnRefreshActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnRefreshActionPerformed
        requestOrders();
    }//GEN-LAST:event_btnRefreshActionPerformed

    public static void main(String args[]) {
        HaidilaoNetworkPOS.applyLookAndFeel();
        java.awt.EventQueue.invokeLater(() -> new KitchenFrame().startWithLogin());
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnRefresh;
    private javax.swing.JComboBox<String> cboFilter;
    private javax.swing.JLabel lblBoardHint;
    private javax.swing.JLabel lblClock;
    private javax.swing.JLabel lblConnection;
    private javax.swing.JLabel lblCountCooking;
    private javax.swing.JLabel lblCountLate;
    private javax.swing.JLabel lblCountWaiting;
    private javax.swing.JLabel lblStaff;
    private javax.swing.JLabel lblTitle;
    private javax.swing.JLabel lblToast;
    private javax.swing.JPanel pnlBoard;
    private javax.swing.JPanel pnlClock;
    private javax.swing.JPanel pnlCounters;
    private javax.swing.JPanel pnlHeader;
    private javax.swing.JPanel pnlHeaderLeft;
    private javax.swing.JPanel pnlHeaderRight;
    private javax.swing.JPanel pnlStatusBar;
    private javax.swing.JPanel pnlTickets;
    private javax.swing.JScrollPane scrollTickets;
    // End of variables declaration//GEN-END:variables
}
