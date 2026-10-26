/*
 * ServerAdminFrame.java
 */
package com.haidilao.pos;

import com.haidilao.pos.admin.ChatHistoryDialog;
import com.haidilao.pos.admin.ComboManagementDialog;
import com.haidilao.pos.admin.MenuManagementDialog;
import com.haidilao.pos.admin.SettingsDialog;
import com.haidilao.pos.admin.StaffManagementDialog;
import com.haidilao.pos.admin.StatisticsDialog;
import com.haidilao.pos.admin.TableManagementDialog;
import com.haidilao.pos.database.DatabaseManager;
import com.haidilao.pos.database.SchemaMigrator;
import com.haidilao.pos.database.StaffDao;
import com.haidilao.pos.database.StatisticsDao;
import com.haidilao.pos.database.TableDao;
import com.haidilao.pos.model.DiningTable;
import com.haidilao.pos.model.NetworkDevice;
import com.haidilao.pos.model.Staff;
import com.haidilao.pos.protocol.ProtocolConstants;
import com.haidilao.pos.server.PosServer;
import com.haidilao.pos.server.ServerEventListener;
import com.haidilao.pos.ui.Theme;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Chương trình máy chủ: quản lý đăng nhập, server TCP tự khởi động, tổng quan (doanh thu, đơn, bàn, thiết bị),
 * nhật ký hệ thống và menu quản lý thực đơn, combo, bàn, nhân viên, thống kê, cài đặt, khóa thiết bị.
 */
public class ServerAdminFrame extends javax.swing.JFrame implements ServerEventListener {

    private PosServer posServer;
    private Staff manager;
    private DefaultTableModel deviceModel;

    public ServerAdminFrame() {
        initComponents();
        initCustomLogic();
    }

    private void initCustomLogic() {
        deviceModel = (DefaultTableModel) tblActiveDevices.getModel();
        Theme.styleTable(tblActiveDevices, -1);
        posServer = new PosServer(ProtocolConstants.SERVER_PORT);
        posServer.setListener(this);
        new Timer(15_000, e -> refreshKpis()).start();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                posServer.stop();
            }
        });
        setMinimumSize(new Dimension(1000, 640));
        Theme.fitToScreen(this, 0.85);
    }

    /**
     * Mở chương trình máy chủ: kiểm tra / nâng cấp database, quản lý đăng nhập, rồi tự khởi động server.
     */
    public static void startWithLogin() {
        ServerAdminFrame f = new ServerAdminFrame();
        while (!f.prepareDatabase()) {
            int opt = JOptionPane.showConfirmDialog(null,
                    "Không kết nối được MySQL (" + DatabaseManager.getDatabaseName() + ").\nHãy bật Laragon (MySQL) rồi bấm Yes để thử lại.",
                    "Chưa có database", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (opt != JOptionPane.YES_OPTION) {
                f.dispose();
                return;
            }
        }
        LoginDialog dlg = new LoginDialog(null, "Đăng nhập Máy chủ (tài khoản Quản lý)", (user, pass, d) -> {
            Staff s = StaffDao.authenticate(user, pass);
            if (s == null) {
                d.showError("Sai tài khoản hoặc mật khẩu.");
            } else if (!Staff.ROLE_MANAGER.equals(s.getRole())) {
                d.showError("Chỉ tài khoản Quản lý được mở máy chủ.");
            } else {
                f.manager = s;
                d.loginSucceeded();
            }
        });
        if (!dlg.showDialog()) {
            f.dispose();
            return;
        }
        f.lblManager.setText("Quản lý: " + f.manager.getFullName() + " (" + f.manager.getUsername() + ")");
        f.setVisible(true);
        f.startServer();
    }

    private boolean prepareDatabase() {
        boolean ok = SchemaMigrator.migrate(this::log);
        lblDbStatus.setText(ok ? "● Database: " + DatabaseManager.getDatabaseName() + " OK" : "● Database: lỗi kết nối");
        lblDbStatus.setForeground(ok ? new Color(130, 230, 160) : Theme.RED);
        return ok;
    }

    private void startServer() {
        try {
            posServer.start();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Không mở được cổng " + ProtocolConstants.SERVER_PORT + ": " + e.getMessage()
                    + "\nCó thể máy chủ đã đang chạy ở cửa sổ khác.", "Lỗi khởi động server", JOptionPane.ERROR_MESSAGE);
        }
        refreshKpis();
    }

    private void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            txtLog.append(msg + "\n");
            txtLog.setCaretPosition(txtLog.getDocument().getLength());
        });
    }

    /** Cập nhật 4 ô chỉ số (truy vấn database trên luồng nền để giao diện không bị khựng) */
    private void refreshKpis() {
        new Thread(() -> {
            java.sql.Date today = new java.sql.Date(System.currentTimeMillis());
            long revenue = DatabaseManager.getTotalRevenue();
            long orders = StatisticsDao.getSummary(today, today).orderCount;
            long serving = TableDao.getAllTables().stream().filter(t -> DiningTable.STATUS_SERVING.equals(t.getStatus())).count();
            SwingUtilities.invokeLater(() -> {
                lblKpiRevenue.setText(Theme.money(revenue));
                lblKpiOrders.setText(String.valueOf(orders));
                lblKpiTables.setText(String.valueOf(serving));
                lblKpiDevices.setText(String.valueOf(deviceModel.getRowCount()));
            });
        }, "KpiRefresh").start();
    }

    private void lockSelectedDevice() {
        int row = tblActiveDevices.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Chọn một thiết bị trong bảng để khóa.", "Khóa thiết bị", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String devId = String.valueOf(deviceModel.getValueAt(row, 0));
        int ok = JOptionPane.showConfirmDialog(this, "Khóa thiết bị " + devId + "?\nThiết bị bị ngắt kết nối ngay và không đăng nhập lại được cho tới khi được mở khóa.",
                "Khóa thiết bị", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok == JOptionPane.YES_OPTION && !posServer.lockDevice(devId)) {
            JOptionPane.showMessageDialog(this, "Không tìm thấy thiết bị " + devId + " trong database.");
        }
    }

    private void unlockDevice() {
        List<String> locked = new ArrayList<>();
        for (NetworkDevice d : DatabaseManager.getAllNetworkDevices()) {
            if (d.isLocked()) locked.add(d.getDeviceId());
        }
        if (locked.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Hiện không có thiết bị nào đang bị khóa.", "Mở khóa thiết bị", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        locked.add("TẤT CẢ THIẾT BỊ");
        Object picked = JOptionPane.showInputDialog(this, "Chọn thiết bị cần mở khóa:", "Mở khóa thiết bị",
                JOptionPane.QUESTION_MESSAGE, null, locked.toArray(), locked.get(0));
        if (picked == null) return;
        if ("TẤT CẢ THIẾT BỊ".equals(picked)) posServer.unlockAllDevices();
        else posServer.unlockDevice(picked.toString());
        JOptionPane.showMessageDialog(this, "Đã mở khóa. Mở lại ứng dụng trên thiết bị đó để kết nối.");
    }

    // ------------------------------------------------------------------ ServerEventListener

    @Override
    public void onLog(String message) {
        log(message);
    }

    @Override
    public void onServerStarted(int port) {
        SwingUtilities.invokeLater(() -> {
            lblSocketStatus.setText("● Server: đang chạy (TCP " + port + ")");
            lblSocketStatus.setForeground(new Color(130, 230, 160));
            btnToggleServer.setText("Dừng server");
            btnToggleServer.setBackground(Theme.RED);
            StringBuilder ips = new StringBuilder();
            try {
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                        if (a instanceof Inet4Address && a.isSiteLocalAddress()) ips.append(ips.length() > 0 ? ", " : "").append(a.getHostAddress());
                    }
                }
            } catch (SocketException ignored) {}
            lblServerAddress.setText("Địa chỉ máy chủ: " + (ips.length() == 0 ? "127.0.0.1" : ips)
                    + "  ·  Các máy Lễ tân, Bếp, Tablet trong cùng mạng Wi-Fi tự tìm thấy, không cần nhập IP.");
        });
    }

    @Override
    public void onServerStopped() {
        SwingUtilities.invokeLater(() -> {
            lblSocketStatus.setText("● Server: đang tắt");
            lblSocketStatus.setForeground(Theme.RED);
            btnToggleServer.setText("Khởi động server");
            btnToggleServer.setBackground(Theme.GREEN);
            deviceModel.setRowCount(0);
            refreshKpis();
        });
    }

    @Override
    public void onDeviceConnected(String deviceId, String role, String ip, String mac, String status) {
        SwingUtilities.invokeLater(() -> {
            for (int i = 0; i < deviceModel.getRowCount(); i++) {
                if (deviceModel.getValueAt(i, 0).equals(deviceId)) {
                    deviceModel.removeRow(i);
                    break;
                }
            }
            deviceModel.addRow(new Object[]{deviceId, role, ip, mac, status});
            lblKpiDevices.setText(String.valueOf(deviceModel.getRowCount()));
        });
    }

    @Override
    public void onDeviceDisconnected(String deviceId) {
        SwingUtilities.invokeLater(() -> {
            for (int i = 0; i < deviceModel.getRowCount(); i++) {
                if (deviceModel.getValueAt(i, 0).equals(deviceId)) {
                    deviceModel.removeRow(i);
                    break;
                }
            }
            lblKpiDevices.setText(String.valueOf(deviceModel.getRowCount()));
        });
    }

    @Override
    public void onRevenueUpdated(long totalRevenue) {
        SwingUtilities.invokeLater(() -> lblKpiRevenue.setText(Theme.money(totalRevenue)));
        refreshKpis();
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
        lblManager = new javax.swing.JLabel();
        pnlHeaderRight = new javax.swing.JPanel();
        pnlStatusLabels = new javax.swing.JPanel();
        lblSocketStatus = new javax.swing.JLabel();
        lblDbStatus = new javax.swing.JLabel();
        btnToggleServer = new javax.swing.JButton();
        pnlNav = new javax.swing.JPanel();
        pnlNavButtons = new javax.swing.JPanel();
        lblNavManage = new javax.swing.JLabel();
        btnManageMenu = new javax.swing.JButton();
        btnManageCombos = new javax.swing.JButton();
        btnManageTables = new javax.swing.JButton();
        btnManageStaff = new javax.swing.JButton();
        btnStatistics = new javax.swing.JButton();
        btnChatHistory = new javax.swing.JButton();
        btnSettings = new javax.swing.JButton();
        lblNavSecurity = new javax.swing.JLabel();
        btnLockDevice = new javax.swing.JButton();
        btnUnlockDevice = new javax.swing.JButton();
        btnClearLog = new javax.swing.JButton();
        pnlDashboard = new javax.swing.JPanel();
        pnlKpis = new javax.swing.JPanel();
        pnlKpiRevenue = new javax.swing.JPanel();
        lblKpiRevenueTitle = new javax.swing.JLabel();
        lblKpiRevenue = new javax.swing.JLabel();
        pnlKpiOrders = new javax.swing.JPanel();
        lblKpiOrdersTitle = new javax.swing.JLabel();
        lblKpiOrders = new javax.swing.JLabel();
        pnlKpiTables = new javax.swing.JPanel();
        lblKpiTablesTitle = new javax.swing.JLabel();
        lblKpiTables = new javax.swing.JLabel();
        pnlKpiDevices = new javax.swing.JPanel();
        lblKpiDevicesTitle = new javax.swing.JLabel();
        lblKpiDevices = new javax.swing.JLabel();
        splitDash = new javax.swing.JSplitPane();
        pnlDevices = new javax.swing.JPanel();
        scrollDevices = new javax.swing.JScrollPane();
        tblActiveDevices = new javax.swing.JTable();
        pnlLog = new javax.swing.JPanel();
        scrollLog = new javax.swing.JScrollPane();
        txtLog = new javax.swing.JTextArea();
        lblServerAddress = new javax.swing.JLabel();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("Máy chủ - Quản trị nhà hàng");

        pnlHeader.setBackground(new java.awt.Color(31, 36, 48));
        pnlHeader.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 16, 10, 16));
        pnlHeader.setLayout(new java.awt.BorderLayout(16, 0));

        pnlHeaderLeft.setOpaque(false);
        pnlHeaderLeft.setLayout(new java.awt.GridLayout(2, 1));

        lblTitle.setFont(new java.awt.Font("Segoe UI", 1, 18)); // NOI18N
        lblTitle.setForeground(new java.awt.Color(241, 196, 15));
        lblTitle.setText("MÁY CHỦ · QUẢN TRỊ NHÀ HÀNG");
        pnlHeaderLeft.add(lblTitle);

        lblManager.setForeground(new java.awt.Color(200, 206, 216));
        lblManager.setText("Quản lý: -");
        pnlHeaderLeft.add(lblManager);

        pnlHeader.add(pnlHeaderLeft, java.awt.BorderLayout.WEST);

        pnlHeaderRight.setOpaque(false);
        pnlHeaderRight.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 12, 4));

        pnlStatusLabels.setOpaque(false);
        pnlStatusLabels.setLayout(new java.awt.GridLayout(2, 1));

        lblSocketStatus.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        lblSocketStatus.setForeground(new java.awt.Color(231, 76, 60));
        lblSocketStatus.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        lblSocketStatus.setText("● Server: đang tắt");
        pnlStatusLabels.add(lblSocketStatus);

        lblDbStatus.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        lblDbStatus.setForeground(new java.awt.Color(200, 206, 216));
        lblDbStatus.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        lblDbStatus.setText("● Database: -");
        pnlStatusLabels.add(lblDbStatus);

        pnlHeaderRight.add(pnlStatusLabels);

        btnToggleServer.setBackground(new java.awt.Color(39, 174, 96));
        btnToggleServer.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnToggleServer.setForeground(new java.awt.Color(255, 255, 255));
        btnToggleServer.setText("Khởi động server");
        btnToggleServer.setFocusable(false);
        btnToggleServer.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnToggleServerActionPerformed(evt);
            }
        });
        pnlHeaderRight.add(btnToggleServer);

        pnlHeader.add(pnlHeaderRight, java.awt.BorderLayout.EAST);

        getContentPane().add(pnlHeader, java.awt.BorderLayout.NORTH);

        pnlNav.setBackground(new java.awt.Color(40, 48, 64));
        pnlNav.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 10, 12, 10));
        pnlNav.setLayout(new java.awt.BorderLayout());

        pnlNavButtons.setOpaque(false);
        pnlNavButtons.setLayout(new java.awt.GridLayout(0, 1, 0, 6));

        lblNavManage.setFont(new java.awt.Font("Segoe UI", 1, 11)); // NOI18N
        lblNavManage.setForeground(new java.awt.Color(200, 206, 216));
        lblNavManage.setText("QUẢN LÝ");
        pnlNavButtons.add(lblNavManage);

        btnManageMenu.setBackground(new java.awt.Color(40, 48, 64));
        btnManageMenu.setFont(new java.awt.Font("Segoe UI", 0, 14)); // NOI18N
        btnManageMenu.setForeground(new java.awt.Color(255, 255, 255));
        btnManageMenu.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        btnManageMenu.setText("Thực đơn & ảnh món");
        btnManageMenu.setFocusable(false);
        btnManageMenu.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnManageMenuActionPerformed(evt);
            }
        });
        pnlNavButtons.add(btnManageMenu);

        btnManageCombos.setBackground(new java.awt.Color(40, 48, 64));
        btnManageCombos.setFont(new java.awt.Font("Segoe UI", 0, 14)); // NOI18N
        btnManageCombos.setForeground(new java.awt.Color(255, 255, 255));
        btnManageCombos.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        btnManageCombos.setText("Combo ưu đãi");
        btnManageCombos.setFocusable(false);
        btnManageCombos.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnManageCombosActionPerformed(evt);
            }
        });
        pnlNavButtons.add(btnManageCombos);

        btnManageTables.setBackground(new java.awt.Color(40, 48, 64));
        btnManageTables.setFont(new java.awt.Font("Segoe UI", 0, 14)); // NOI18N
        btnManageTables.setForeground(new java.awt.Color(255, 255, 255));
        btnManageTables.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        btnManageTables.setText("Bàn & khu vực");
        btnManageTables.setFocusable(false);
        btnManageTables.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnManageTablesActionPerformed(evt);
            }
        });
        pnlNavButtons.add(btnManageTables);

        btnManageStaff.setBackground(new java.awt.Color(40, 48, 64));
        btnManageStaff.setFont(new java.awt.Font("Segoe UI", 0, 14)); // NOI18N
        btnManageStaff.setForeground(new java.awt.Color(255, 255, 255));
        btnManageStaff.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        btnManageStaff.setText("Nhân viên & tài khoản");
        btnManageStaff.setFocusable(false);
        btnManageStaff.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnManageStaffActionPerformed(evt);
            }
        });
        pnlNavButtons.add(btnManageStaff);

        btnStatistics.setBackground(new java.awt.Color(40, 48, 64));
        btnStatistics.setFont(new java.awt.Font("Segoe UI", 0, 14)); // NOI18N
        btnStatistics.setForeground(new java.awt.Color(255, 255, 255));
        btnStatistics.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        btnStatistics.setText("Thống kê doanh thu");
        btnStatistics.setFocusable(false);
        btnStatistics.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnStatisticsActionPerformed(evt);
            }
        });
        pnlNavButtons.add(btnStatistics);

        btnChatHistory.setBackground(new java.awt.Color(40, 48, 64));
        btnChatHistory.setFont(new java.awt.Font("Segoe UI", 0, 14)); // NOI18N
        btnChatHistory.setForeground(new java.awt.Color(255, 255, 255));
        btnChatHistory.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        btnChatHistory.setText("Lịch sử tin nhắn");
        btnChatHistory.setFocusable(false);
        btnChatHistory.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnChatHistoryActionPerformed(evt);
            }
        });
        pnlNavButtons.add(btnChatHistory);

        btnSettings.setBackground(new java.awt.Color(40, 48, 64));
        btnSettings.setFont(new java.awt.Font("Segoe UI", 0, 14)); // NOI18N
        btnSettings.setForeground(new java.awt.Color(255, 255, 255));
        btnSettings.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        btnSettings.setText("Cài đặt nhà hàng");
        btnSettings.setFocusable(false);
        btnSettings.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSettingsActionPerformed(evt);
            }
        });
        pnlNavButtons.add(btnSettings);

        lblNavSecurity.setFont(new java.awt.Font("Segoe UI", 1, 11)); // NOI18N
        lblNavSecurity.setForeground(new java.awt.Color(200, 206, 216));
        lblNavSecurity.setText("BẢO MẬT");
        pnlNavButtons.add(lblNavSecurity);

        btnLockDevice.setBackground(new java.awt.Color(40, 48, 64));
        btnLockDevice.setFont(new java.awt.Font("Segoe UI", 0, 14)); // NOI18N
        btnLockDevice.setForeground(new java.awt.Color(255, 255, 255));
        btnLockDevice.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        btnLockDevice.setText("Khóa thiết bị");
        btnLockDevice.setFocusable(false);
        btnLockDevice.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnLockDeviceActionPerformed(evt);
            }
        });
        pnlNavButtons.add(btnLockDevice);

        btnUnlockDevice.setBackground(new java.awt.Color(40, 48, 64));
        btnUnlockDevice.setFont(new java.awt.Font("Segoe UI", 0, 14)); // NOI18N
        btnUnlockDevice.setForeground(new java.awt.Color(255, 255, 255));
        btnUnlockDevice.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        btnUnlockDevice.setText("Mở khóa thiết bị");
        btnUnlockDevice.setFocusable(false);
        btnUnlockDevice.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnUnlockDeviceActionPerformed(evt);
            }
        });
        pnlNavButtons.add(btnUnlockDevice);

        btnClearLog.setBackground(new java.awt.Color(40, 48, 64));
        btnClearLog.setFont(new java.awt.Font("Segoe UI", 0, 14)); // NOI18N
        btnClearLog.setForeground(new java.awt.Color(255, 255, 255));
        btnClearLog.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        btnClearLog.setText("Xóa nhật ký");
        btnClearLog.setFocusable(false);
        btnClearLog.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnClearLogActionPerformed(evt);
            }
        });
        pnlNavButtons.add(btnClearLog);

        pnlNav.add(pnlNavButtons, java.awt.BorderLayout.NORTH);

        getContentPane().add(pnlNav, java.awt.BorderLayout.WEST);

        pnlDashboard.setBackground(new java.awt.Color(244, 245, 247));
        pnlDashboard.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 12, 12, 12));
        pnlDashboard.setLayout(new java.awt.BorderLayout(0, 12));

        pnlKpis.setOpaque(false);
        pnlKpis.setLayout(new java.awt.GridLayout(1, 4, 12, 0));

        pnlKpiRevenue.setBackground(new java.awt.Color(255, 255, 255));
        pnlKpiRevenue.setBorder(javax.swing.BorderFactory.createCompoundBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(222, 226, 232)), javax.swing.BorderFactory.createEmptyBorder(12, 14, 12, 14)));
        pnlKpiRevenue.setLayout(new java.awt.BorderLayout(0, 4));

        lblKpiRevenueTitle.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        lblKpiRevenueTitle.setForeground(new java.awt.Color(127, 140, 141));
        lblKpiRevenueTitle.setText("Doanh thu hôm nay");
        pnlKpiRevenue.add(lblKpiRevenueTitle, java.awt.BorderLayout.NORTH);

        lblKpiRevenue.setFont(new java.awt.Font("Segoe UI", 1, 22)); // NOI18N
        lblKpiRevenue.setForeground(new java.awt.Color(169, 29, 34));
        lblKpiRevenue.setText("0 đ");
        pnlKpiRevenue.add(lblKpiRevenue, java.awt.BorderLayout.CENTER);

        pnlKpis.add(pnlKpiRevenue);

        pnlKpiOrders.setBackground(new java.awt.Color(255, 255, 255));
        pnlKpiOrders.setBorder(javax.swing.BorderFactory.createCompoundBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(222, 226, 232)), javax.swing.BorderFactory.createEmptyBorder(12, 14, 12, 14)));
        pnlKpiOrders.setLayout(new java.awt.BorderLayout(0, 4));

        lblKpiOrdersTitle.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        lblKpiOrdersTitle.setForeground(new java.awt.Color(127, 140, 141));
        lblKpiOrdersTitle.setText("Đơn hôm nay");
        pnlKpiOrders.add(lblKpiOrdersTitle, java.awt.BorderLayout.NORTH);

        lblKpiOrders.setFont(new java.awt.Font("Segoe UI", 1, 22)); // NOI18N
        lblKpiOrders.setForeground(new java.awt.Color(41, 128, 185));
        lblKpiOrders.setText("0");
        pnlKpiOrders.add(lblKpiOrders, java.awt.BorderLayout.CENTER);

        pnlKpis.add(pnlKpiOrders);

        pnlKpiTables.setBackground(new java.awt.Color(255, 255, 255));
        pnlKpiTables.setBorder(javax.swing.BorderFactory.createCompoundBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(222, 226, 232)), javax.swing.BorderFactory.createEmptyBorder(12, 14, 12, 14)));
        pnlKpiTables.setLayout(new java.awt.BorderLayout(0, 4));

        lblKpiTablesTitle.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        lblKpiTablesTitle.setForeground(new java.awt.Color(127, 140, 141));
        lblKpiTablesTitle.setText("Bàn đang phục vụ");
        pnlKpiTables.add(lblKpiTablesTitle, java.awt.BorderLayout.NORTH);

        lblKpiTables.setFont(new java.awt.Font("Segoe UI", 1, 22)); // NOI18N
        lblKpiTables.setForeground(new java.awt.Color(39, 174, 96));
        lblKpiTables.setText("0");
        pnlKpiTables.add(lblKpiTables, java.awt.BorderLayout.CENTER);

        pnlKpis.add(pnlKpiTables);

        pnlKpiDevices.setBackground(new java.awt.Color(255, 255, 255));
        pnlKpiDevices.setBorder(javax.swing.BorderFactory.createCompoundBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(222, 226, 232)), javax.swing.BorderFactory.createEmptyBorder(12, 14, 12, 14)));
        pnlKpiDevices.setLayout(new java.awt.BorderLayout(0, 4));

        lblKpiDevicesTitle.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        lblKpiDevicesTitle.setForeground(new java.awt.Color(127, 140, 141));
        lblKpiDevicesTitle.setText("Thiết bị đang kết nối");
        pnlKpiDevices.add(lblKpiDevicesTitle, java.awt.BorderLayout.NORTH);

        lblKpiDevices.setFont(new java.awt.Font("Segoe UI", 1, 22)); // NOI18N
        lblKpiDevices.setForeground(new java.awt.Color(142, 68, 173));
        lblKpiDevices.setText("0");
        pnlKpiDevices.add(lblKpiDevices, java.awt.BorderLayout.CENTER);

        pnlKpis.add(pnlKpiDevices);

        pnlDashboard.add(pnlKpis, java.awt.BorderLayout.NORTH);

        splitDash.setOrientation(javax.swing.JSplitPane.VERTICAL_SPLIT);
        splitDash.setResizeWeight(0.45);

        pnlDevices.setBackground(new java.awt.Color(255, 255, 255));
        pnlDevices.setBorder(javax.swing.BorderFactory.createTitledBorder(null, "Thiết bị đang kết nối", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Segoe UI", 1, 12), new java.awt.Color(44, 62, 80))); // NOI18N
        pnlDevices.setLayout(new java.awt.BorderLayout());

        tblActiveDevices.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        tblActiveDevices.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {

            },
            new String [] {
                "Thiết bị", "Vai trò", "Địa chỉ IP", "Địa chỉ MAC", "Trạng thái"
            }
        ) {
            boolean[] canEdit = new boolean [] {
                false, false, false, false, false
            };

            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return canEdit [columnIndex];
            }
        });
        tblActiveDevices.setRowHeight(28);
        scrollDevices.setViewportView(tblActiveDevices);

        pnlDevices.add(scrollDevices, java.awt.BorderLayout.CENTER);

        splitDash.setTopComponent(pnlDevices);

        pnlLog.setBackground(new java.awt.Color(255, 255, 255));
        pnlLog.setBorder(javax.swing.BorderFactory.createTitledBorder(null, "Nhật ký hệ thống", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Segoe UI", 1, 12), new java.awt.Color(44, 62, 80))); // NOI18N
        pnlLog.setLayout(new java.awt.BorderLayout());

        txtLog.setFont(new java.awt.Font("Consolas", 0, 12)); // NOI18N
        txtLog.setColumns(20);
        txtLog.setEditable(false);
        txtLog.setLineWrap(true);
        txtLog.setRows(8);
        txtLog.setWrapStyleWord(true);
        scrollLog.setViewportView(txtLog);

        pnlLog.add(scrollLog, java.awt.BorderLayout.CENTER);

        splitDash.setBottomComponent(pnlLog);

        pnlDashboard.add(splitDash, java.awt.BorderLayout.CENTER);

        lblServerAddress.setForeground(new java.awt.Color(127, 140, 141));
        lblServerAddress.setText("Địa chỉ máy chủ: -");
        pnlDashboard.add(lblServerAddress, java.awt.BorderLayout.SOUTH);

        getContentPane().add(pnlDashboard, java.awt.BorderLayout.CENTER);

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void btnToggleServerActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnToggleServerActionPerformed
        if (posServer.isRunning()) posServer.stop();
        else startServer();
    }//GEN-LAST:event_btnToggleServerActionPerformed

    private void btnManageMenuActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnManageMenuActionPerformed
        new MenuManagementDialog(this, posServer).setVisible(true);
        refreshKpis();
    }//GEN-LAST:event_btnManageMenuActionPerformed

    private void btnManageCombosActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnManageCombosActionPerformed
        new ComboManagementDialog(this, posServer).setVisible(true);
    }//GEN-LAST:event_btnManageCombosActionPerformed

    private void btnManageTablesActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnManageTablesActionPerformed
        new TableManagementDialog(this, posServer).setVisible(true);
        refreshKpis();
    }//GEN-LAST:event_btnManageTablesActionPerformed

    private void btnManageStaffActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnManageStaffActionPerformed
        new StaffManagementDialog(this, manager).setVisible(true);
    }//GEN-LAST:event_btnManageStaffActionPerformed

    private void btnStatisticsActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnStatisticsActionPerformed
        new StatisticsDialog(this).setVisible(true);
    }//GEN-LAST:event_btnStatisticsActionPerformed

    private void btnChatHistoryActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnChatHistoryActionPerformed
        new ChatHistoryDialog(this).setVisible(true);
    }//GEN-LAST:event_btnChatHistoryActionPerformed

    private void btnSettingsActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSettingsActionPerformed
        new SettingsDialog(this).setVisible(true);
    }//GEN-LAST:event_btnSettingsActionPerformed

    private void btnLockDeviceActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnLockDeviceActionPerformed
        lockSelectedDevice();
    }//GEN-LAST:event_btnLockDeviceActionPerformed

    private void btnUnlockDeviceActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnUnlockDeviceActionPerformed
        unlockDevice();
    }//GEN-LAST:event_btnUnlockDeviceActionPerformed

    private void btnClearLogActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnClearLogActionPerformed
        txtLog.setText("");
    }//GEN-LAST:event_btnClearLogActionPerformed

    public static void main(String args[]) {
        HaidilaoNetworkPOS.applyLookAndFeel();
        java.awt.EventQueue.invokeLater(ServerAdminFrame::startWithLogin);
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnChatHistory;
    private javax.swing.JButton btnClearLog;
    private javax.swing.JButton btnLockDevice;
    private javax.swing.JButton btnManageCombos;
    private javax.swing.JButton btnManageMenu;
    private javax.swing.JButton btnManageStaff;
    private javax.swing.JButton btnManageTables;
    private javax.swing.JButton btnSettings;
    private javax.swing.JButton btnStatistics;
    private javax.swing.JButton btnToggleServer;
    private javax.swing.JButton btnUnlockDevice;
    private javax.swing.JLabel lblDbStatus;
    private javax.swing.JLabel lblKpiDevices;
    private javax.swing.JLabel lblKpiDevicesTitle;
    private javax.swing.JLabel lblKpiOrders;
    private javax.swing.JLabel lblKpiOrdersTitle;
    private javax.swing.JLabel lblKpiRevenue;
    private javax.swing.JLabel lblKpiRevenueTitle;
    private javax.swing.JLabel lblKpiTables;
    private javax.swing.JLabel lblKpiTablesTitle;
    private javax.swing.JLabel lblManager;
    private javax.swing.JLabel lblNavManage;
    private javax.swing.JLabel lblNavSecurity;
    private javax.swing.JLabel lblServerAddress;
    private javax.swing.JLabel lblSocketStatus;
    private javax.swing.JLabel lblTitle;
    private javax.swing.JPanel pnlDashboard;
    private javax.swing.JPanel pnlDevices;
    private javax.swing.JPanel pnlHeader;
    private javax.swing.JPanel pnlHeaderLeft;
    private javax.swing.JPanel pnlHeaderRight;
    private javax.swing.JPanel pnlKpiDevices;
    private javax.swing.JPanel pnlKpiOrders;
    private javax.swing.JPanel pnlKpiRevenue;
    private javax.swing.JPanel pnlKpiTables;
    private javax.swing.JPanel pnlKpis;
    private javax.swing.JPanel pnlLog;
    private javax.swing.JPanel pnlNav;
    private javax.swing.JPanel pnlNavButtons;
    private javax.swing.JPanel pnlStatusLabels;
    private javax.swing.JScrollPane scrollDevices;
    private javax.swing.JScrollPane scrollLog;
    private javax.swing.JSplitPane splitDash;
    private javax.swing.JTable tblActiveDevices;
    private javax.swing.JTextArea txtLog;
    // End of variables declaration//GEN-END:variables
}
