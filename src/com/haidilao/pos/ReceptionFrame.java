/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/GUIForms/JFrame.java to edit this template
 */
package com.haidilao.pos;

import com.haidilao.pos.client.ClientEventListener;
import com.haidilao.pos.client.PosClient;
import com.haidilao.pos.database.DatabaseManager;
import com.haidilao.pos.model.Order;
import com.haidilao.pos.protocol.NetworkPacket;
import com.haidilao.pos.protocol.ProtocolConstants;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 *
 * @author Nguyen Huu Thang
 */
public class ReceptionFrame extends javax.swing.JFrame implements ClientEventListener{

    private PosClient client;
    private DefaultTableModel ordersTableModel;
    private final Map<String, JPanel> tablePanels = new HashMap<>();
    private final Map<String, JLabel> tableStatusLabels = new HashMap<>();
    private String serverHost = ProtocolConstants.DEFAULT_SERVER_HOST;
    private int historyCount = 0; // số tin nhắn cũ đã nhận trong lần tải lịch sử hiện tại
    
    /**
     * Creates new form ReceptionFrame
     */
    public ReceptionFrame() {
        this(ProtocolConstants.DEFAULT_SERVER_HOST);
    }
    public ReceptionFrame(String serverHost) {
        this.serverHost = (serverHost == null || serverHost.trim().isEmpty()) ? ProtocolConstants.DEFAULT_SERVER_HOST : serverHost.trim();
        initComponents();
        initCustomLogic();
    }

    private void initCustomLogic() {
        setLocationRelativeTo(null);
        ordersTableModel = (DefaultTableModel) tblOrders.getModel();

        // Cấu hình thanh hiển thị danh sách bàn dạng scroll ngang linh hoạt, không giới hạn số lượng bàn
        pnlTopMap.remove(pnlTablesGrid);
        pnlTablesGrid.setLayout(new FlowLayout(FlowLayout.LEFT, 10, 0));
        JScrollPane scrollTables = new JScrollPane(pnlTablesGrid);
        scrollTables.setBorder(null);
        scrollTables.setOpaque(false);
        scrollTables.getViewport().setOpaque(false);
        scrollTables.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        scrollTables.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        pnlTopMap.add(scrollTables, BorderLayout.CENTER);

        pnlBan1.setPreferredSize(new Dimension(185, 62));
        pnlBan2.setPreferredSize(new Dimension(185, 62));
        pnlBan3.setPreferredSize(new Dimension(185, 62));

        // Ánh xạ các Card bàn ăn mặc định
        tablePanels.put("Tablet_Ban1", pnlBan1);
        tablePanels.put("Tablet_Ban2", pnlBan2);
        tablePanels.put("Tablet_Ban3", pnlBan3);

        tableStatusLabels.put("Tablet_Ban1", lblStatusBan1);
        tableStatusLabels.put("Tablet_Ban2", lblStatusBan2);
        tableStatusLabels.put("Tablet_Ban3", lblStatusBan3);

        setupCardMouseListener("Tablet_Ban1", pnlBan1);
        setupCardMouseListener("Tablet_Ban2", pnlBan2);
        setupCardMouseListener("Tablet_Ban3", pnlBan3);

        // Click vào dòng đơn món trên bảng -> tự động chuyển combobox chọn đúng bàn đó
        tblOrders.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = tblOrders.getSelectedRow();
                if (row >= 0) {
                    String tbl = (String) ordersTableModel.getValueAt(row, 1);
                    if (tbl != null) {
                        ensureTableCardExists(tbl);
                        cboTargetTable.setSelectedItem(tbl);
                        highlightSelectedCard(tbl);
                    }
                }
            }
        });

        // Đổi lựa chọn bàn trong dropdown -> highlight card bàn tương ứng
        cboTargetTable.addActionListener(e -> {
            String sel = (String) cboTargetTable.getSelectedItem();
            if (sel != null) {
                highlightSelectedCard(sel);
            }
        });

        // Khởi động kết nối Socket an toàn lên POS_Server (Server tự động đồng bộ đơn qua Socket)
        connectToPosServer();
    }

    public static String formatFriendlyTableName(String rawName) {
        if (rawName == null) return "Bàn";
        String s = rawName.trim();
        if (s.toLowerCase().startsWith("tablet_ban")) {
            return "BÀN SỐ " + s.substring(10).trim();
        }
        if (s.toLowerCase().startsWith("tablet_")) {
            return "BÀN " + s.substring(7).trim().toUpperCase();
        }
        if (s.toLowerCase().startsWith("ban")) {
            return "BÀN SỐ " + s.substring(3).trim();
        }
        return s.toUpperCase();
    }

    private void ensureInCombo(String tableId) {
        if (tableId == null || tableId.trim().isEmpty()) return;
        boolean exists = false;
        for (int i = 0; i < cboTargetTable.getItemCount(); i++) {
            if (tableId.equalsIgnoreCase(cboTargetTable.getItemAt(i))) {
                exists = true;
                break;
            }
        }
        if (!exists) {
            cboTargetTable.addItem(tableId);
        }
    }

    private void highlightSelectedCard(String targetId) {
        if (targetId == null) return;
        for (Map.Entry<String, JPanel> entry : tablePanels.entrySet()) {
            String id = entry.getKey();
            JPanel p = entry.getValue();
            if (id.equalsIgnoreCase(targetId) || id.replaceAll("[^a-zA-Z0-9]", "").equalsIgnoreCase(targetId.replaceAll("[^a-zA-Z0-9]", ""))) {
                p.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(241, 196, 15), 3),
                    BorderFactory.createEmptyBorder(7, 9, 7, 9)
                ));
            } else {
                p.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
            }
        }
    }

    private void setupCardMouseListener(String tableId, JPanel card) {
        card.setCursor(new Cursor(Cursor.HAND_CURSOR));
        card.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                ensureInCombo(tableId);
                cboTargetTable.setSelectedItem(tableId);
                highlightSelectedCard(tableId);
                txtReplyInput.requestFocusInWindow();
            }
        });
    }

    /**
     * Tự động tạo thêm Card bàn ăn trên giao diện khi có Tablet mới kết nối (ví dụ Tablet_Ban4, Tablet_Ban5...)
     */
    private void ensureTableCardExists(String tableId) {
        ensureInCombo(tableId);
        if (!tablePanels.containsKey(tableId)) {
            JPanel card = new JPanel(new BorderLayout(0, 4));
            card.setPreferredSize(new Dimension(185, 62));
            card.setBackground(new Color(127, 140, 141));
            card.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

            JLabel lblTitle = new JLabel(formatFriendlyTableName(tableId));
            lblTitle.setFont(new Font("Segoe UI", Font.BOLD, 14));
            lblTitle.setForeground(Color.WHITE);

            JLabel lblStatus = new JLabel("● Trạng thái: OFFLINE");
            lblStatus.setFont(new Font("Segoe UI", Font.BOLD, 12));
            lblStatus.setForeground(new Color(236, 240, 241));

            card.add(lblTitle, BorderLayout.NORTH);
            card.add(lblStatus, BorderLayout.SOUTH);

            setupCardMouseListener(tableId, card);

            tablePanels.put(tableId, card);
            tableStatusLabels.put(tableId, lblStatus);

            pnlTablesGrid.add(card);
            pnlTablesGrid.revalidate();
            pnlTablesGrid.repaint();
        }
    }

    private void connectToPosServer() {
        client = new PosClient(
                serverHost,
                ProtocolConstants.SERVER_PORT,
                "RECEPTION",
                buildReceptionId(),
                ProtocolConstants.KEY_RECEPTION
        );
        client.setListener(this);
        client.connect();
    }

    /**
     * Mỗi máy Lễ tân dùng tên riêng theo tên máy tính (vd. PC_LeTan_LAPTOP01)
     * để nhiều quầy cùng kết nối Server không ghi đè phiên của nhau
     */
    private static String buildReceptionId() {
        String host = "";
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {}
        host = host.replaceAll("[^A-Za-z0-9_-]", "").toUpperCase();
        return host.isEmpty() ? "PC_LeTan" : "PC_LeTan_" + host;
    }

    /**
     * Một dòng lịch sử: HISTORY|THOI_GIAN|NGUOI_GUI|NGUOI_NHAN|LOAI|NOI_DUNG
     */
    private void showHistoryLine(String time, String sender, String receiver, String type, String content) {
        if ("RING".equals(type)) {
            appendChat("[" + time + "] [" + formatFriendlyTableName(sender) + " BẤM CHUÔNG]: " + content);
        } else if (sender.startsWith("PC_LeTan")) {
            appendChat("[" + time + "] ↪ Lễ Tân -> " + formatFriendlyTableName(receiver) + ": " + content);
        } else {
            appendChat("[" + time + "] " + formatFriendlyTableName(sender) + ": " + content);
        }
    }

    private void appendChat(String msg) {
        SwingUtilities.invokeLater(() -> {
            txtReceptionChat.append(msg + "\n");
            txtReceptionChat.setCaretPosition(txtReceptionChat.getDocument().getLength());
        });
    }

    // --- ClientEventListener Callbacks ---
    @Override
    public void onConnected() {
        // Server sẽ gửi lại toàn bộ đơn cũ (OLD_ORDER) ngay sau khi xác thực -> xóa bảng trước để không bị trùng dòng
        SwingUtilities.invokeLater(() -> {
            ordersTableModel.setRowCount(0);
            txtReceptionChat.setText(""); // lịch sử chat sẽ được Server gửi lại (GET_HISTORY)
        });
        appendChat("✔ Đã kết nối Socket thành công vào POS_Server (Port " + ProtocolConstants.SERVER_PORT + ").");
        client.send(NetworkPacket.buildGetHistory(buildReceptionId()));
    }

    @Override
    public void onAuthFailed(String reason) {
        appendChat("✖ Server từ chối kết nối: " + reason);
        SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(this, reason, "Không thể kết nối hệ thống", JOptionPane.WARNING_MESSAGE));
    }

    @Override
    public void onDisconnected(String reason) {
        appendChat("✖ " + reason + ". Đang tự động thử kết nối lại...");
        // Đưa tất cả bàn về trạng thái OFFLINE
        SwingUtilities.invokeLater(() -> {
            for (String tbl : tablePanels.keySet()) {
                JPanel card = tablePanels.get(tbl);
                JLabel lbl = tableStatusLabels.get(tbl);
                if (card != null && lbl != null) {
                    card.setBackground(new Color(127, 140, 141));
                    lbl.setText("● Trạng thái: OFFLINE");
                }
            }
        });
    }

    @Override
    public void onMessageReceived(String rawMessage) {
        SwingUtilities.invokeLater(() -> {
            String[] p = rawMessage.split(ProtocolConstants.DELIMITER_REGEX);
            String cmd = p[0];
            String time = new SimpleDateFormat("HH:mm:ss").format(new Date());

            if ("TABLE_STATE".equals(cmd)) {
                // TABLE_STATE|TABLE_ID|ONLINE/OFFLINE
                String tbl = p[1];
                String state = p[2];
                ensureTableCardExists(tbl);
                JPanel card = tablePanels.get(tbl);
                JLabel lbl = tableStatusLabels.get(tbl);
                if (card != null && lbl != null) {
                    if ("ONLINE".equals(state)) {
                        card.setBackground(new Color(39, 174, 96)); // Xanh lá
                        lbl.setText("● Trạng thái: ĐANG KẾT NỐI");
                    } else {
                        card.setBackground(new Color(127, 140, 141)); // Xám
                        lbl.setText("● Trạng thái: OFFLINE");
                    }
                }
            } else if ("RING".equals(cmd)) {
                // RING|TABLE_ID|REASON
                String tbl = p[1];
                String reason = p.length > 2 ? p[2] : "Yêu cầu phục vụ";
                Toolkit.getDefaultToolkit().beep();

                ensureTableCardExists(tbl);
                JPanel card = tablePanels.get(tbl);
                JLabel lbl = tableStatusLabels.get(tbl);
                if (card != null && lbl != null) {
                    card.setBackground(new Color(211, 47, 47)); // Đỏ rực
                    lbl.setText("🔔 GỌI: " + reason);
                }
                appendChat("[" + time + "] 🔔 [" + formatFriendlyTableName(tbl) + " BẤM CHUÔNG]: " + reason);
                cboTargetTable.setSelectedItem(tbl);
                highlightSelectedCard(tbl);

            } else if ("NEW_ORDER".equals(cmd)) {
                // NEW_ORDER|ORDER_ID|TABLE_ID|ITEMS|TOTAL
                String orderId = p[1];
                String tbl = p[2];
                String items = p[3];
                long total = Long.parseLong(p[4]);
                String moneyFormatted = new DecimalFormat("#,### VNĐ").format(total);

                Toolkit.getDefaultToolkit().beep();
                ensureTableCardExists(tbl);
                JPanel card = tablePanels.get(tbl);
                JLabel lbl = tableStatusLabels.get(tbl);
                if (card != null && lbl != null) {
                    card.setBackground(new Color(230, 126, 34)); // Vàng cam
                    lbl.setText("🍲 CÓ ĐƠN MỚI (#" + orderId + ")");
                }

                ordersTableModel.insertRow(0, new Object[]{orderId, tbl, items, moneyFormatted, "CHỜ XÁC NHẬN"});
                appendChat("[" + time + "] 🍲 [" + formatFriendlyTableName(tbl) + "] gửi đơn món mới #" + orderId + " (" + moneyFormatted + ")");
                cboTargetTable.setSelectedItem(tbl);
                highlightSelectedCard(tbl);

            } else if ("OLD_ORDER".equals(cmd)) {
                // OLD_ORDER|ORDER_ID|TABLE_ID|ITEMS|TOTAL|STATUS  (đơn đã lưu trong MySQL, đồng bộ khi vừa kết nối)
                String moneyFormatted = new DecimalFormat("#,### VNĐ").format(Long.parseLong(p[4]));
                String status = p.length > 5 ? p[5] : "CHỜ XÁC NHẬN";
                ordersTableModel.insertRow(0, new Object[]{p[1], p[2], p[3], moneyFormatted, status});

            } else if ("CHAT_FROM_TABLE".equals(cmd)) {
                // CHAT_FROM_TABLE|TABLE_ID|MESSAGE
                String tbl = p[1];
                String msg = p[2];
                Toolkit.getDefaultToolkit().beep();
                ensureTableCardExists(tbl);
                appendChat("[" + time + "] 💬 " + formatFriendlyTableName(tbl) + ": " + msg);
                cboTargetTable.setSelectedItem(tbl);
                highlightSelectedCard(tbl);

            } else if ("AUTH_ACK".equals(cmd)) {
                appendChat("✔ " + p[1]);
            } else if ("SERVER_ACK".equals(cmd)) {
                appendChat("ℹ " + p[1]);
            } else if ("HISTORY".equals(cmd) && p.length >= 6) {
                if (historyCount == 0) {
                    appendChat("--------- Tin nhắn trước đây ---------");
                }
                historyCount++;
                showHistoryLine(p[1], p[2], p[3], p[4], p[5]);
            } else if ("HISTORY_END".equals(cmd)) {
                if (historyCount > 0) {
                    appendChat("--------- Hết tin nhắn cũ ---------");
                }
                historyCount = 0;
            } else if ("ORDER_UPDATED".equals(cmd)) {
                // ORDER_UPDATED|ORDER_ID|STATUS: quầy khác vừa đổi trạng thái đơn -> cập nhật bảng cho đồng bộ
                for (int r = 0; r < ordersTableModel.getRowCount(); r++) {
                    if (p[1].equals(String.valueOf(ordersTableModel.getValueAt(r, 0)))) {
                        ordersTableModel.setValueAt(p[2], r, 4);
                    }
                }
            } else if ("RECEPTION_REPLY".equals(cmd)) {
                // RECEPTION_REPLY|QUAY_GUI|BAN|NOI_DUNG: câu trả lời của quầy Lễ tân khác
                appendChat("[" + time + "] ↪ " + p[1] + " -> " + formatFriendlyTableName(p[2]) + ": " + (p.length > 3 ? p[3] : ""));
            }
        });
    }

    @Override
    public void onKicked(String reason) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, reason, "Port Security Err-Disable", JOptionPane.ERROR_MESSAGE);
            System.exit(0);
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

        pnlTopMap = new javax.swing.JPanel();
        pnlTablesGrid = new javax.swing.JPanel();
        pnlBan1 = new javax.swing.JPanel();
        lblTitleBan1 = new javax.swing.JLabel();
        lblStatusBan1 = new javax.swing.JLabel();
        pnlBan2 = new javax.swing.JPanel();
        lblTitleBan2 = new javax.swing.JLabel();
        lblStatusBan2 = new javax.swing.JLabel();
        pnlBan3 = new javax.swing.JPanel();
        lblTitleBan3 = new javax.swing.JLabel();
        lblStatusBan3 = new javax.swing.JLabel();
        btnResetBell = new javax.swing.JButton();
        splitPane = new javax.swing.JSplitPane();
        pnlOrders = new javax.swing.JPanel();
        scrollOrders = new javax.swing.JScrollPane();
        tblOrders = new javax.swing.JTable();
        pnlOrderButtons = new javax.swing.JPanel();
        btnConfirmCooking = new javax.swing.JButton();
        btnCompletePaid = new javax.swing.JButton();
        pnlChat = new javax.swing.JPanel();
        scrollChat = new javax.swing.JScrollPane();
        txtReceptionChat = new javax.swing.JTextArea();
        pnlReply = new javax.swing.JPanel();
        cboTargetTable = new javax.swing.JComboBox<>();
        txtReplyInput = new javax.swing.JTextField();
        btnSendReply = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("HAIDILAO POS - QUẦY THU NGÂN & ĐIỀU PHỐI DỊCH VỤ");
        setBackground(new java.awt.Color(242, 244, 248));
        getContentPane().setLayout(new java.awt.BorderLayout(10, 10));

        pnlTopMap.setBackground(new java.awt.Color(226, 230, 234));
        pnlTopMap.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 10, 8, 10));
        pnlTopMap.setLayout(new java.awt.BorderLayout(10, 0));

        pnlTablesGrid.setOpaque(false);
        pnlTablesGrid.setLayout(new java.awt.GridLayout(1, 3, 12, 0));

        pnlBan1.setBackground(new java.awt.Color(127, 140, 141));
        pnlBan1.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 12, 10, 12));
        pnlBan1.setLayout(new java.awt.BorderLayout(0, 4));

        lblTitleBan1.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        lblTitleBan1.setForeground(new java.awt.Color(255, 255, 255));
        lblTitleBan1.setText("BÀN SỐ 1");
        pnlBan1.add(lblTitleBan1, java.awt.BorderLayout.NORTH);

        lblStatusBan1.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        lblStatusBan1.setForeground(new java.awt.Color(236, 240, 241));
        lblStatusBan1.setText("● Trạng thái: OFFLINE");
        pnlBan1.add(lblStatusBan1, java.awt.BorderLayout.SOUTH);

        pnlTablesGrid.add(pnlBan1);

        pnlBan2.setBackground(new java.awt.Color(127, 140, 141));
        pnlBan2.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 12, 10, 12));
        pnlBan2.setLayout(new java.awt.BorderLayout(0, 4));

        lblTitleBan2.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        lblTitleBan2.setForeground(new java.awt.Color(255, 255, 255));
        lblTitleBan2.setText("BÀN SỐ 2");
        pnlBan2.add(lblTitleBan2, java.awt.BorderLayout.NORTH);

        lblStatusBan2.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        lblStatusBan2.setForeground(new java.awt.Color(236, 240, 241));
        lblStatusBan2.setText("● Trạng thái: OFFLINE");
        pnlBan2.add(lblStatusBan2, java.awt.BorderLayout.SOUTH);

        pnlTablesGrid.add(pnlBan2);

        pnlBan3.setBackground(new java.awt.Color(127, 140, 141));
        pnlBan3.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 12, 10, 12));
        pnlBan3.setLayout(new java.awt.BorderLayout(0, 4));

        lblTitleBan3.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        lblTitleBan3.setForeground(new java.awt.Color(255, 255, 255));
        lblTitleBan3.setText("BÀN SỐ 3");
        pnlBan3.add(lblTitleBan3, java.awt.BorderLayout.NORTH);

        lblStatusBan3.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        lblStatusBan3.setForeground(new java.awt.Color(236, 240, 241));
        lblStatusBan3.setText("● Trạng thái: OFFLINE");
        pnlBan3.add(lblStatusBan3, java.awt.BorderLayout.PAGE_END);

        pnlTablesGrid.add(pnlBan3);

        pnlTopMap.add(pnlTablesGrid, java.awt.BorderLayout.CENTER);

        btnResetBell.setBackground(new java.awt.Color(44, 62, 80));
        btnResetBell.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        btnResetBell.setForeground(new java.awt.Color(255, 255, 255));
        btnResetBell.setText("[TẮT CHUÔNG] ĐÃ PHỤC VỤ");
        btnResetBell.setFocusable(false);
        btnResetBell.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnResetBellActionPerformed(evt);
            }
        });
        pnlTopMap.add(btnResetBell, java.awt.BorderLayout.EAST);

        getContentPane().add(pnlTopMap, java.awt.BorderLayout.NORTH);

        splitPane.setDividerLocation(580);
        splitPane.setResizeWeight(0.55);

        pnlOrders.setBackground(new java.awt.Color(255, 255, 255));
        pnlOrders.setBorder(javax.swing.BorderFactory.createTitledBorder(null, "DANH SÁCH ĐƠN GỌI MÓN TẠI BÀN", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Segoe UI", 1, 12), new java.awt.Color(192, 57, 43))); // NOI18N
        pnlOrders.setLayout(new java.awt.BorderLayout(6, 8));

        tblOrders.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {

            },
            new String [] {
                "Mã Đơn", "Bàn", "Chi Tiết Món Gọi", "Tổng Tiền", "Trạng Thái"
            }
        ) {
            boolean[] canEdit = new boolean [] {
                false, false, false, false, false
            };

            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return canEdit [columnIndex];
            }
        });
        tblOrders.setRowHeight(26);
        scrollOrders.setViewportView(tblOrders);

        pnlOrders.add(scrollOrders, java.awt.BorderLayout.CENTER);

        pnlOrderButtons.setOpaque(false);
        pnlOrderButtons.setLayout(new java.awt.GridLayout(1, 2, 10, 0));

        btnConfirmCooking.setBackground(new java.awt.Color(230, 126, 34));
        btnConfirmCooking.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        btnConfirmCooking.setForeground(new java.awt.Color(255, 255, 255));
        btnConfirmCooking.setText("[XÁC NHẬN] CHUYỂN BẾP");
        btnConfirmCooking.setFocusable(false);
        btnConfirmCooking.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnConfirmCookingActionPerformed(evt);
            }
        });
        pnlOrderButtons.add(btnConfirmCooking);

        btnCompletePaid.setBackground(new java.awt.Color(39, 174, 96));
        btnCompletePaid.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        btnCompletePaid.setForeground(new java.awt.Color(255, 255, 255));
        btnCompletePaid.setText("[HOÀN TẤT] THANH TOÁN");
        btnCompletePaid.setFocusable(false);
        btnCompletePaid.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnCompletePaidActionPerformed(evt);
            }
        });
        pnlOrderButtons.add(btnCompletePaid);

        pnlOrders.add(pnlOrderButtons, java.awt.BorderLayout.SOUTH);

        splitPane.setLeftComponent(pnlOrders);

        pnlChat.setBackground(new java.awt.Color(255, 255, 255));
        pnlChat.setBorder(javax.swing.BorderFactory.createTitledBorder(null, "YÊU CẦU PHỤC VỤ & TIN NHẮN TỪ KHÁCH HÀNG", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Segoe UI", 1, 12), new java.awt.Color(41, 128, 185))); // NOI18N
        pnlChat.setLayout(new java.awt.BorderLayout(6, 6));

        txtReceptionChat.setEditable(false);
        txtReceptionChat.setColumns(20);
        txtReceptionChat.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        txtReceptionChat.setLineWrap(true);
        txtReceptionChat.setRows(5);
        txtReceptionChat.setWrapStyleWord(true);
        scrollChat.setViewportView(txtReceptionChat);

        pnlChat.add(scrollChat, java.awt.BorderLayout.CENTER);

        pnlReply.setOpaque(false);
        pnlReply.setLayout(new java.awt.BorderLayout(6, 0));

        cboTargetTable.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        cboTargetTable.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "Tablet_Ban1", "Tablet_Ban2", "Tablet_Ban3" }));
        pnlReply.add(cboTargetTable, java.awt.BorderLayout.WEST);

        txtReplyInput.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        txtReplyInput.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                txtReplyInputActionPerformed(evt);
            }
        });
        pnlReply.add(txtReplyInput, java.awt.BorderLayout.CENTER);

        btnSendReply.setBackground(new java.awt.Color(41, 128, 185));
        btnSendReply.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        btnSendReply.setForeground(new java.awt.Color(255, 255, 255));
        btnSendReply.setText("Gửi Phản Hồi");
        btnSendReply.setFocusable(false);
        btnSendReply.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSendReplyActionPerformed(evt);
            }
        });
        pnlReply.add(btnSendReply, java.awt.BorderLayout.EAST);

        pnlChat.add(pnlReply, java.awt.BorderLayout.SOUTH);

        splitPane.setRightComponent(pnlChat);

        getContentPane().add(splitPane, java.awt.BorderLayout.CENTER);

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void btnResetBellActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnResetBellActionPerformed
        for (String tbl : tablePanels.keySet()) {
            JPanel card = tablePanels.get(tbl);
            JLabel lbl = tableStatusLabels.get(tbl);
            if (card != null && lbl != null && !lbl.getText().contains("OFFLINE")) {
                card.setBackground(new Color(39, 174, 96));
                lbl.setText("● Trạng thái: ĐANG PHỤC VỤ");
            }
        }
    }//GEN-LAST:event_btnResetBellActionPerformed

    private void btnConfirmCookingActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnConfirmCookingActionPerformed
        updateSelectedOrderStatus("BẾP ĐANG CHẾ BIẾN");
    }//GEN-LAST:event_btnConfirmCookingActionPerformed

    private void btnCompletePaidActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnCompletePaidActionPerformed
        updateSelectedOrderStatus("ĐÃ PHỤC VỤ HOÀN TẤT");
    }//GEN-LAST:event_btnCompletePaidActionPerformed

    private void btnSendReplyActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSendReplyActionPerformed
        sendReplyMessage();
    }//GEN-LAST:event_btnSendReplyActionPerformed

    private void txtReplyInputActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_txtReplyInputActionPerformed
        // TODO add your handling code here:
        sendReplyMessage();
    }//GEN-LAST:event_txtReplyInputActionPerformed

    private void updateSelectedOrderStatus(String newStatus) {
        int row = tblOrders.getSelectedRow();
        if (row == -1) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn một đơn hàng trong bảng để cập nhật trạng thái!", "Chưa chọn đơn", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int orderId = Integer.parseInt(tblOrders.getValueAt(row, 0).toString());
        String tableId = tblOrders.getValueAt(row, 1).toString();
        tblOrders.setValueAt(newStatus, row, 4);

        if (client != null && client.isConnected()) {
            client.send(NetworkPacket.buildOrderStatusUpdate(orderId, tableId, newStatus));
        }

        // Tắt màu cảnh báo trên card bàn nếu đơn đã xong
        JPanel card = tablePanels.get(tableId);
        JLabel lbl = tableStatusLabels.get(tableId);
        if (card != null && lbl != null && !lbl.getText().contains("OFFLINE")) {
            card.setBackground(new Color(39, 174, 96));
            lbl.setText("● Trạng thái: " + newStatus);
        }
    }

    private void sendReplyMessage() {
        String msg = txtReplyInput.getText().trim();
        if (msg.isEmpty()) return;

        String targetTable = (String) cboTargetTable.getSelectedItem();
        if (targetTable == null || targetTable.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn bàn muốn gửi phản hồi trong danh sách hoặc bấm trực tiếp vào thẻ bàn ở trên!", "Chưa chọn bàn", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (client != null && client.isConnected()) {
            client.send(NetworkPacket.buildChatToTable(targetTable, msg));
            String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
            appendChat("[" + time + "] ↪ Lễ Tân -> " + formatFriendlyTableName(targetTable) + ": " + msg);
            txtReplyInput.setText("");
        } else {
            JOptionPane.showMessageDialog(this, "Chưa kết nối tới máy chủ nhà hàng! Vui lòng kiểm tra lại Server.", "Chưa kết nối", JOptionPane.WARNING_MESSAGE);
        }
    }

    /**
     * @param args the command line arguments
     */
    public static void main(String args[]) {
        /* Set the Nimbus look and feel */
        //<editor-fold defaultstate="collapsed" desc=" Look and feel setting code (optional) ">
        /* If Nimbus (introduced in Java SE 6) is not available, stay with the default look and feel.
         * For details see http://download.oracle.com/javase/tutorial/uiswing/lookandfeel/plaf.html 
         */
        try {
            for (javax.swing.UIManager.LookAndFeelInfo info : javax.swing.UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    javax.swing.UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (ClassNotFoundException ex) {
            java.util.logging.Logger.getLogger(ReceptionFrame.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (InstantiationException ex) {
            java.util.logging.Logger.getLogger(ReceptionFrame.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (IllegalAccessException ex) {
            java.util.logging.Logger.getLogger(ReceptionFrame.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (javax.swing.UnsupportedLookAndFeelException ex) {
            java.util.logging.Logger.getLogger(ReceptionFrame.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        }
        //</editor-fold>

        /* Create and display the form */
        java.awt.EventQueue.invokeLater(new Runnable() {
            public void run() {
                String ip = JOptionPane.showInputDialog(null,
                        "Nhập IP Máy Chủ POS_Server (để '127.0.0.1' nếu cùng máy, hoặc nhập IP LAN nếu máy khác):",
                        "Kết nối Quầy Lễ Tân (PC_LeTan)",
                        JOptionPane.QUESTION_MESSAGE);
                if (ip == null || ip.trim().isEmpty()) {
                    ip = ProtocolConstants.DEFAULT_SERVER_HOST;
                }
                new ReceptionFrame(ip).setVisible(true);
            }
        });
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnCompletePaid;
    private javax.swing.JButton btnConfirmCooking;
    private javax.swing.JButton btnResetBell;
    private javax.swing.JButton btnSendReply;
    private javax.swing.JComboBox<String> cboTargetTable;
    private javax.swing.JLabel lblStatusBan1;
    private javax.swing.JLabel lblStatusBan2;
    private javax.swing.JLabel lblStatusBan3;
    private javax.swing.JLabel lblTitleBan1;
    private javax.swing.JLabel lblTitleBan2;
    private javax.swing.JLabel lblTitleBan3;
    private javax.swing.JPanel pnlBan1;
    private javax.swing.JPanel pnlBan2;
    private javax.swing.JPanel pnlBan3;
    private javax.swing.JPanel pnlChat;
    private javax.swing.JPanel pnlOrderButtons;
    private javax.swing.JPanel pnlOrders;
    private javax.swing.JPanel pnlReply;
    private javax.swing.JPanel pnlTablesGrid;
    private javax.swing.JPanel pnlTopMap;
    private javax.swing.JScrollPane scrollChat;
    private javax.swing.JScrollPane scrollOrders;
    private javax.swing.JSplitPane splitPane;
    private javax.swing.JTable tblOrders;
    private javax.swing.JTextArea txtReceptionChat;
    private javax.swing.JTextField txtReplyInput;
    // End of variables declaration//GEN-END:variables
}
