/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/GUIForms/JFrame.java to edit this template
 */
package com.haidilao.pos;

import com.haidilao.pos.client.ClientEventListener;
import com.haidilao.pos.client.PosClient;
import com.haidilao.pos.model.MenuItem;
import com.haidilao.pos.protocol.NetworkPacket;
import com.haidilao.pos.protocol.ProtocolConstants;
import com.haidilao.pos.util.ImageUtil;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 *
 * @author pc
 */
public class TabletClientFrame extends javax.swing.JFrame implements ClientEventListener {

    private String tableName = "Tablet_Ban1";
    private PosClient client;
    private DefaultTableModel cartTableModel;
    private long currentTotalAmount = 0;
    private final List<MenuItem> receivingMenu = new ArrayList<>(); // thực đơn đang nhận dở từ Server
    private String connectionStatus = "Đang kết nối...";
    private int receptionOnline = -1;     // số quầy Lễ tân đang trực tuyến (-1 = chưa biết)
    private int historyCount = 0;         // số tin nhắn cũ đã nhận trong lần tải lịch sử hiện tại

    private String serverHost = ProtocolConstants.DEFAULT_SERVER_HOST;

    /**
     * Khởi tạo TabletClientFrame mặc định
     */
    public TabletClientFrame() {
        this("Tablet_Ban1", ProtocolConstants.DEFAULT_SERVER_HOST);
    }

    /**
     * Khởi tạo TabletClientFrame với tên bàn cụ thể
     */
    public TabletClientFrame(String tableName) {
        this(tableName, ProtocolConstants.DEFAULT_SERVER_HOST);
    }

    /**
     * Khởi tạo TabletClientFrame với tên bàn và IP Server tùy biến (cho mạng LAN)
     */
    public TabletClientFrame(String tableName, String serverHost) {
        this.tableName = (tableName == null || tableName.trim().isEmpty()) ? "Tablet_Ban1" : tableName.trim();
        this.serverHost = (serverHost == null || serverHost.trim().isEmpty()) ? ProtocolConstants.DEFAULT_SERVER_HOST : serverHost.trim();
        initComponents();
        initCustomLogic();
    }

    private void initCustomLogic() {
        setLocationRelativeTo(null);
        cartTableModel = (DefaultTableModel) tblCart.getModel();

        setTitle("HAIDILAO HOTPOT - " + formatFriendlyTableName(tableName));
        refreshHeader();

        // Hiện thực đơn mặc định trước, khi kết nối Server xong sẽ xin thực đơn thật (GET_MENU)
        showMenu(getDefaultFallbackMenu());

        // Khởi chạy Socket Client kết nối POS_Server
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

    /**
     * Vẽ các thẻ món ăn vào 4 tab danh mục (chạy trên luồng giao diện EDT)
     */
    private void showMenu(List<MenuItem> menuList) {
        pnlMenuCat1.removeAll();
        pnlMenuCat2.removeAll();
        pnlMenuCat3.removeAll();
        pnlMenuCat4.removeAll();

        for (MenuItem item : menuList) {
            JPanel card = createDishCard(item.getItemName(), item.getPrice(), item.getImageData());
            String cat = item.getCategory();

            if (cat.contains("1") || cat.toLowerCase().contains("lẩu")) {
                pnlMenuCat1.add(card);
            } else if (cat.contains("2") || cat.toLowerCase().contains("thịt")) {
                pnlMenuCat2.add(card);
            } else if (cat.contains("3") || cat.toLowerCase().contains("hải sản")) {
                pnlMenuCat3.add(card);
            } else {
                pnlMenuCat4.add(card);
            }
        }

        pnlMenuCat1.revalidate();
        pnlMenuCat1.repaint();
        pnlMenuCat2.revalidate();
        pnlMenuCat2.repaint();
        pnlMenuCat3.revalidate();
        pnlMenuCat3.repaint();
        pnlMenuCat4.revalidate();
        pnlMenuCat4.repaint();
    }

    /**
     * Gửi yêu cầu GET_MENU lên Server để lấy thực đơn trong MySQL qua Socket
     */
    private void requestMenuFromServer() {
        if (client != null && client.isConnected()) {
            client.send(NetworkPacket.buildGetMenu(tableName));
        }
    }

    private List<MenuItem> getDefaultFallbackMenu() {
        List<MenuItem> fallback = new ArrayList<>();
        fallback.add(new MenuItem(1, "1. Nước Lẩu Haidilao", "Lẩu Dầu Cay Tứ Xuyên Thượng Hạng", 139000, "AVAILABLE"));
        fallback.add(new MenuItem(2, "1. Nước Lẩu Haidilao", "Lẩu Cà Chua Tân Cương Đậm Đà", 129000, "AVAILABLE"));
        fallback.add(new MenuItem(3, "1. Nước Lẩu Haidilao", "Lẩu Nấm Tùng Nhung Thiên Nhiên", 139000, "AVAILABLE"));
        fallback.add(new MenuItem(4, "1. Nước Lẩu Haidilao", "Lẩu Thái Tomyum Cốt Dừa", 129000, "AVAILABLE"));

        fallback.add(new MenuItem(5, "2. Thịt Thượng Hạng", "Bò Wagyu A5 Nhật Bản (150g)", 489000, "AVAILABLE"));
        fallback.add(new MenuItem(6, "2. Thịt Thượng Hạng", "Thăn Ngoại Bò Mỹ Black Angus", 219000, "AVAILABLE"));
        fallback.add(new MenuItem(7, "2. Thịt Thượng Hạng", "Thịt Cừu Úc Thảo Nguyên Cuộn", 189000, "AVAILABLE"));
        fallback.add(new MenuItem(8, "2. Thịt Thượng Hạng", "Lá Sách Bò Tươi Giòn Haidilao", 159000, "AVAILABLE"));

        fallback.add(new MenuItem(9, "3. Hải Sản & Đặc Sắc", "Múa Mì Haidilao Biểu Diễn Tại Bàn", 45000, "AVAILABLE"));
        fallback.add(new MenuItem(10, "3. Hải Sản & Đặc Sắc", "Tôm Sú Bằm Thủ Công Trứng Cá", 149000, "AVAILABLE"));
        fallback.add(new MenuItem(11, "3. Hải Sản & Đặc Sắc", "Bào Ngư Hàn Quốc Tươi Sống", 299000, "AVAILABLE"));
        fallback.add(new MenuItem(12, "3. Hải Sản & Đặc Sắc", "Đậu Phụ Phô Mai Tan Chảy", 89000, "AVAILABLE"));

        fallback.add(new MenuItem(13, "4. Món Quốc Tế & Đồ Uống", "Gan Ngỗng Pháp Áp Chảo Sốt Táo", 350000, "AVAILABLE"));
        fallback.add(new MenuItem(14, "4. Món Quốc Tế & Đồ Uống", "Sashimi Cá Hồi Na Uy Tươi", 220000, "AVAILABLE"));
        fallback.add(new MenuItem(15, "4. Món Quốc Tế & Đồ Uống", "Trà Đào Cam Sả Nhiệt Đới", 55000, "AVAILABLE"));
        fallback.add(new MenuItem(16, "4. Món Quốc Tế & Đồ Uống", "Kem Häagen-Dazs Vani Pháp", 95000, "AVAILABLE"));
        return fallback;
    }

    private JPanel createDishCard(String name, long price, byte[] imageData) {
        JPanel card = new JPanel(new BorderLayout(4, 4));
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(220, 224, 230), 1),
                new EmptyBorder(8, 10, 8, 10)
        ));

        String priceStr = new DecimalFormat("#,### VNĐ").format(price);
        JLabel lblTitle = new JLabel("<html><b style='font-size:11px;color:#2c3e50;'>" + name + "</b><br>"
                + "<span style='color:#c0392b;font-weight:bold;font-size:12px;'>" + priceStr + "</span></html>");

        JButton btnAdd = new JButton("+ Thêm");
        btnAdd.setBackground(new Color(169, 29, 34));
        btnAdd.setForeground(Color.WHITE);
        btnAdd.setFont(new Font("Segoe UI", Font.BOLD, 11));
        btnAdd.setFocusPainted(false);
        btnAdd.addActionListener(e -> addToCart(name, price));

        // Ảnh món ăn do Server gửi xuống (món chưa có ảnh thì hiện ô xám cùng kích thước cho các thẻ đều nhau)
        JLabel lblImage = new JLabel("", SwingConstants.CENTER);
        lblImage.setPreferredSize(new Dimension(ImageUtil.DISPLAY_WIDTH, ImageUtil.DISPLAY_HEIGHT));
        ImageIcon icon = ImageUtil.toIcon(imageData);
        if (icon != null) {
            lblImage.setIcon(icon);
        } else {
            lblImage.setText("Chưa có ảnh");
            lblImage.setForeground(new Color(150, 150, 150));
            lblImage.setOpaque(true);
            lblImage.setBackground(new Color(236, 240, 241));
        }

        card.add(lblImage, BorderLayout.NORTH);
        card.add(lblTitle, BorderLayout.CENTER);
        card.add(btnAdd, BorderLayout.SOUTH);
        return card;
    }

    private void addToCart(String dishName, long price) {
        for (int i = 0; i < cartTableModel.getRowCount(); i++) {
            if (cartTableModel.getValueAt(i, 0).equals(dishName)) {
                int qty = (int) cartTableModel.getValueAt(i, 1) + 1;
                cartTableModel.setValueAt(qty, i, 1);
                cartTableModel.setValueAt(new DecimalFormat("#,###").format(qty * price), i, 2);
                currentTotalAmount += price;
                updateTotalMoneyLabel();
                return;
            }
        }

        cartTableModel.addRow(new Object[]{dishName, 1, new DecimalFormat("#,###").format(price)});
        currentTotalAmount += price;
        updateTotalMoneyLabel();
    }

    private void updateTotalMoneyLabel() {
        String formatted = new DecimalFormat("#,### VNĐ").format(currentTotalAmount);
        lblTotalMoney.setText("Tổng cộng: " + formatted);
    }

    private void connectToPosServer() {
        client = new PosClient(
                serverHost,
                ProtocolConstants.SERVER_PORT,
                "TABLET",
                tableName,
                ProtocolConstants.KEY_TABLET
        );
        client.setListener(this);
        client.connect();
    }

    /**
     * Thêm 1 dòng vào khung chat, kèm giờ hiện tại [HH:mm]
     */
    private void appendChat(String msg) {
        appendLine("[" + new SimpleDateFormat("HH:mm").format(new Date()) + "] " + msg);
    }

    private void appendLine(String line) {
        SwingUtilities.invokeLater(() -> {
            txtChatHistory.append(line + "\n");
            txtChatHistory.setCaretPosition(txtChatHistory.getDocument().getLength());
        });
    }

    /**
     * Dòng tiêu đề đỏ: tên bàn + trạng thái kết nối + Lễ tân có đang trực tuyến không
     */
    private void refreshHeader() {
        String reception = "";
        if (receptionOnline == 0) {
            reception = "  ·  Lễ tân: chưa trực tuyến";
        } else if (receptionOnline > 0) {
            reception = "  ·  Lễ tân: trực tuyến";
        }
        lblHeaderStatus.setText("HAIDILAO HOTPOT | " + formatFriendlyTableName(tableName) + " (" + connectionStatus + ")" + reception);
    }

    /**
     * Một dòng lịch sử: HISTORY|THOI_GIAN|NGUOI_GUI|NGUOI_NHAN|LOAI|NOI_DUNG
     */
    private void showHistoryLine(String payload) {
        String[] f = payload.split(ProtocolConstants.DELIMITER_REGEX, 5);
        if (f.length < 5) return;
        String time = f[0], sender = f[1], type = f[3], content = f[4];

        if (historyCount == 0) {
            appendLine("--------- Tin nhắn trước đây ---------");
        }
        historyCount++;

        String text;
        if ("RING".equals(type)) {
            text = "[Bạn đã bấm chuông]: " + content;
        } else if ("ORDER".equals(type)) {
            text = "[Bạn đã đặt đơn]: " + content;
        } else if (sender.equalsIgnoreCase(tableName)) {
            text = "[Quý khách]: " + content;
        } else {
            text = "[Quầy Lễ Tân]: " + content;
        }
        appendLine("[" + time + "] " + text);
    }

    private void sendRingRequest(String serviceName) {
        if (client != null && client.isConnected()) {
            client.send(NetworkPacket.buildRing(tableName, serviceName));
            appendChat("🔔 [Bạn vừa bấm chuông]: " + serviceName);
        } else {
            JOptionPane.showMessageDialog(this, "Không thể gửi yêu cầu do mất kết nối máy chủ! Vui lòng liên hệ nhân viên phục vụ.", "Chưa kết nối", JOptionPane.WARNING_MESSAGE);
        }
    }

    // --- ClientEventListener Callbacks ---
    @Override
    public void onConnected() {
        SwingUtilities.invokeLater(() -> {
            connectionStatus = "Sẵn sàng phục vụ";
            refreshHeader();
            txtChatHistory.setText(""); // lịch sử sẽ được Server gửi lại ngay sau đây
            historyCount = 0;
        });
        requestMenuFromServer();
        client.send(NetworkPacket.buildGetHistory(tableName));
    }

    @Override
    public void onDisconnected(String reason) {
        SwingUtilities.invokeLater(() -> {
            connectionStatus = "Mất kết nối máy chủ";
            receptionOnline = -1;
            refreshHeader();
            appendChat("✖ " + reason + ". Đang tự động thử kết nối lại...");
        });
    }

    @Override
    public void onAuthFailed(String reason) {
        SwingUtilities.invokeLater(() -> {
            connectionStatus = "Bị từ chối kết nối";
            refreshHeader();
            appendChat("✖ Server từ chối kết nối: " + reason);
            JOptionPane.showMessageDialog(this, reason + "\nVui lòng liên hệ nhân viên phục vụ.", "Không thể kết nối hệ thống", JOptionPane.WARNING_MESSAGE);
        });
    }

    @Override
    public void onMessageReceived(String rawMessage) {
        SwingUtilities.invokeLater(() -> {
            String[] p = rawMessage.split(ProtocolConstants.DELIMITER_REGEX, 2);
            String cmd = p[0];
            String payload = p.length > 1 ? p[1] : "";

            if ("SERVER_ACK".equals(cmd)) {
                appendChat("✔ [Hệ thống]: " + payload);
            } else if ("CHAT_FROM_RECEPTION".equals(cmd)) {
                Toolkit.getDefaultToolkit().beep();
                appendChat("👩‍💼 [Quầy Lễ Tân]: " + payload);
            } else if ("ORDER_STATUS".equals(cmd)) {
                Toolkit.getDefaultToolkit().beep();
                appendChat("🔔 [CẬP NHẬT MÓN ĂN]: " + payload);
            } else if ("MENU_BEGIN".equals(cmd)) {
                // Bắt đầu nhận thực đơn từ Server
                receivingMenu.clear();
            } else if ("MENU_ITEM".equals(cmd)) {
                // MENU_ITEM|DANH_MUC|TEN_MON|GIA|ANH_BASE64 (món chưa có ảnh thì trường ảnh rỗng)
                String[] f = payload.split(ProtocolConstants.DELIMITER_REGEX);
                if (f.length >= 3) {
                    try {
                        MenuItem item = new MenuItem(0, f[0], f[1], Long.parseLong(f[2]), "AVAILABLE");
                        if (f.length >= 4 && !f[3].isEmpty()) {
                            item.setImageData(Base64.getDecoder().decode(f[3]));
                        }
                        receivingMenu.add(item);
                    } catch (IllegalArgumentException ignored) {} // giá sai định dạng hoặc ảnh Base64 hỏng
                }
            } else if ("MENU_END".equals(cmd)) {
                // Nhận xong: MySQL trống thì giữ thực đơn mặc định
                showMenu(receivingMenu.isEmpty() ? getDefaultFallbackMenu() : new ArrayList<>(receivingMenu));
            } else if ("HISTORY".equals(cmd)) {
                showHistoryLine(payload);
            } else if ("HISTORY_END".equals(cmd)) {
                if (historyCount > 0) {
                    appendLine("--------- Hết tin nhắn cũ ---------");
                }
                appendChat("✨ Chào mừng quý khách đến với Haidilao! Quý khách có thể chọn món hoặc gửi tin nhắn hỗ trợ trực tiếp với Lễ tân.");
            } else if ("CHAT_ACK".equals(cmd)) {
                // Trạng thái tin nhắn khách vừa gửi
                if ("DELIVERED".equals(payload)) {
                    appendLine("        → Đã chuyển tới Lễ tân");
                } else {
                    appendLine("        → Lễ tân chưa trực tuyến. Tin nhắn đã được lưu, Lễ tân sẽ thấy khi vào ca.");
                }
            } else if ("RECEPTION_STATE".equals(cmd)) {
                try {
                    receptionOnline = Integer.parseInt(payload.trim());
                } catch (NumberFormatException ignored) {}
                refreshHeader();
            } else if ("MENU_UPDATED".equals(cmd)) {
                requestMenuFromServer();
                appendChat("📋 [Thực đơn]: Đã được cập nhật món mới từ quầy quản lý!");
            }
        });
    }

    @Override
    public void onKicked(String reason) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, reason, "Cổng Mạng Bị Khóa (Err-Disable)", JOptionPane.ERROR_MESSAGE);
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

        pnlHeader = new javax.swing.JPanel();
        lblHeaderStatus = new javax.swing.JLabel();
        pnlQuickService = new javax.swing.JPanel();
        btnCallStaff = new javax.swing.JButton();
        btnRefillBroth = new javax.swing.JButton();
        btnNapkin = new javax.swing.JButton();
        btnPayBill = new javax.swing.JButton();
        splitMain = new javax.swing.JSplitPane();
        tabMenuCategories = new javax.swing.JTabbedPane();
        scrollCat1 = new javax.swing.JScrollPane();
        pnlMenuCat1 = new javax.swing.JPanel();
        scrollCat2 = new javax.swing.JScrollPane();
        pnlMenuCat2 = new javax.swing.JPanel();
        scrollCat3 = new javax.swing.JScrollPane();
        pnlMenuCat3 = new javax.swing.JPanel();
        scrollCat4 = new javax.swing.JScrollPane();
        pnlMenuCat4 = new javax.swing.JPanel();
        splitRight = new javax.swing.JSplitPane();
        pnlCart = new javax.swing.JPanel();
        scrollCart = new javax.swing.JScrollPane();
        tblCart = new javax.swing.JTable();
        pnlCartBottom = new javax.swing.JPanel();
        lblTotalMoney = new javax.swing.JLabel();
        pnlCartButtons = new javax.swing.JPanel();
        btnClearCart = new javax.swing.JButton();
        btnSendOrder = new javax.swing.JButton();
        pnlChat = new javax.swing.JPanel();
        scrollChat = new javax.swing.JScrollPane();
        txtChatHistory = new javax.swing.JTextArea();
        pnlChatInput = new javax.swing.JPanel();
        txtChatInput = new javax.swing.JTextField();
        btnSendChat = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("TABLET ĐẶT MÓN & PHỤC VỤ");
        setBackground(new java.awt.Color(24, 26, 32));
        getContentPane().setLayout(new java.awt.BorderLayout(8, 8));

        pnlHeader.setBackground(new java.awt.Color(169, 29, 34));
        pnlHeader.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 14, 8, 14));
        pnlHeader.setLayout(new java.awt.BorderLayout(12, 0));

        lblHeaderStatus.setFont(new java.awt.Font("Segoe UI", 1, 15)); // NOI18N
        lblHeaderStatus.setForeground(new java.awt.Color(255, 215, 0));
        lblHeaderStatus.setText("Bàn Đang Kết Nối Hệ Thống...");
        pnlHeader.add(lblHeaderStatus, java.awt.BorderLayout.WEST);

        pnlQuickService.setOpaque(false);
        pnlQuickService.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));

        btnCallStaff.setBackground(new java.awt.Color(241, 196, 15));
        btnCallStaff.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        btnCallStaff.setForeground(new java.awt.Color(30, 30, 30));
        btnCallStaff.setText("[CHUÔNG] Gọi Nhân Viên");
        btnCallStaff.setFocusable(false);
        btnCallStaff.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnCallStaffActionPerformed(evt);
            }
        });
        pnlQuickService.add(btnCallStaff);

        btnRefillBroth.setBackground(new java.awt.Color(241, 196, 15));
        btnRefillBroth.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        btnRefillBroth.setForeground(new java.awt.Color(30, 30, 30));
        btnRefillBroth.setText("[NƯỚC LẨU] Châm Thêm");
        btnRefillBroth.setFocusable(false);
        btnRefillBroth.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnRefillBrothActionPerformed(evt);
            }
        });
        pnlQuickService.add(btnRefillBroth);

        btnNapkin.setBackground(new java.awt.Color(241, 196, 15));
        btnNapkin.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        btnNapkin.setForeground(new java.awt.Color(30, 30, 30));
        btnNapkin.setText("[DỤNG CỤ] Khăn / Tạp Dề");
        btnNapkin.setFocusable(false);
        btnNapkin.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnNapkinActionPerformed(evt);
            }
        });
        pnlQuickService.add(btnNapkin);

        btnPayBill.setBackground(new java.awt.Color(241, 196, 15));
        btnPayBill.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        btnPayBill.setForeground(new java.awt.Color(30, 30, 30));
        btnPayBill.setText("[TÍNH TIỀN] Thanh Toán");
        btnPayBill.setFocusable(false);
        btnPayBill.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnPayBillActionPerformed(evt);
            }
        });
        pnlQuickService.add(btnPayBill);

        pnlHeader.add(pnlQuickService, java.awt.BorderLayout.EAST);

        getContentPane().add(pnlHeader, java.awt.BorderLayout.NORTH);

        splitMain.setDividerLocation(540);
        splitMain.setResizeWeight(0.52);

        tabMenuCategories.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N

        pnlMenuCat1.setBackground(new java.awt.Color(245, 245, 245));
        pnlMenuCat1.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10));
        pnlMenuCat1.setLayout(new java.awt.GridLayout(0, 2, 10, 10));
        scrollCat1.setViewportView(pnlMenuCat1);

        tabMenuCategories.addTab("1. Nước Lẩu Haidilao", scrollCat1);

        pnlMenuCat2.setBackground(new java.awt.Color(245, 245, 245));
        pnlMenuCat2.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10));
        pnlMenuCat2.setLayout(new java.awt.GridLayout(0, 2, 10, 10));
        scrollCat2.setViewportView(pnlMenuCat2);

        tabMenuCategories.addTab("2. Thịt Thượng Hạng", scrollCat2);

        pnlMenuCat3.setBackground(new java.awt.Color(245, 245, 245));
        pnlMenuCat3.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10));
        pnlMenuCat3.setLayout(new java.awt.GridLayout(0, 2, 10, 10));
        scrollCat3.setViewportView(pnlMenuCat3);

        tabMenuCategories.addTab("3. Hải Sản & Đặc Sắc ", scrollCat3);

        pnlMenuCat4.setBackground(new java.awt.Color(245, 245, 245));
        pnlMenuCat4.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10));
        pnlMenuCat4.setLayout(new java.awt.GridLayout(0, 2, 10, 10));
        scrollCat4.setViewportView(pnlMenuCat4);

        tabMenuCategories.addTab("4. Món Quốc Tế & Đồ Uống", scrollCat4);

        splitMain.setLeftComponent(tabMenuCategories);

        splitRight.setDividerLocation(300);
        splitRight.setResizeWeight(0.5);

        pnlCart.setBackground(new java.awt.Color(255, 255, 255));
        pnlCart.setBorder(javax.swing.BorderFactory.createTitledBorder(null, "GIỎ HÀNG ĐÃ CHỌN", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Segoe UI", 1, 12), new java.awt.Color(169, 29, 34))); // NOI18N
        pnlCart.setLayout(new java.awt.BorderLayout(6, 6));

        tblCart.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {

            },
            new String [] {
                "Tên Món", "Số Lượng", "Thành Tiền"
            }
        ) {
            boolean[] canEdit = new boolean [] {
                false, false, false
            };

            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return canEdit [columnIndex];
            }
        });
        tblCart.setRowHeight(24);
        scrollCart.setViewportView(tblCart);

        pnlCart.add(scrollCart, java.awt.BorderLayout.CENTER);

        pnlCartBottom.setBackground(new java.awt.Color(255, 255, 255));
        pnlCartBottom.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 6, 6, 6));
        pnlCartBottom.setLayout(new java.awt.BorderLayout(0, 6));

        lblTotalMoney.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        lblTotalMoney.setForeground(new java.awt.Color(192, 57, 43));
        lblTotalMoney.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        lblTotalMoney.setText("Tổng cộng: 0 VNĐ");
        pnlCartBottom.add(lblTotalMoney, java.awt.BorderLayout.NORTH);

        pnlCartButtons.setOpaque(false);
        pnlCartButtons.setLayout(new java.awt.GridLayout(1, 2, 6, 0));

        btnClearCart.setText("Xóa Giỏ");
        btnClearCart.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnClearCartActionPerformed(evt);
            }
        });
        pnlCartButtons.add(btnClearCart);

        btnSendOrder.setBackground(new java.awt.Color(169, 29, 34));
        btnSendOrder.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        btnSendOrder.setForeground(new java.awt.Color(255, 255, 255));
        btnSendOrder.setText("[GỬI ĐƠN] XUỐNG BẾP");
        btnSendOrder.setFocusable(false);
        btnSendOrder.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSendOrderActionPerformed(evt);
            }
        });
        pnlCartButtons.add(btnSendOrder);

        pnlCartBottom.add(pnlCartButtons, java.awt.BorderLayout.SOUTH);

        pnlCart.add(pnlCartBottom, java.awt.BorderLayout.SOUTH);

        splitRight.setLeftComponent(pnlCart);

        pnlChat.setBackground(new java.awt.Color(255, 255, 255));
        pnlChat.setBorder(javax.swing.BorderFactory.createTitledBorder(null, "CHAT VỚI QUẦY LỄ TÂN", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Segoe UI", 1, 12), new java.awt.Color(41, 128, 185))); // NOI18N
        pnlChat.setLayout(new java.awt.BorderLayout(4, 4));

        txtChatHistory.setEditable(false);
        txtChatHistory.setColumns(20);
        txtChatHistory.setLineWrap(true);
        txtChatHistory.setRows(5);
        txtChatHistory.setWrapStyleWord(true);
        scrollChat.setViewportView(txtChatHistory);

        pnlChat.add(scrollChat, java.awt.BorderLayout.CENTER);

        pnlChatInput.setOpaque(false);
        pnlChatInput.setLayout(new java.awt.BorderLayout(4, 0));

        txtChatInput.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        txtChatInput.setToolTipText("");
        txtChatInput.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                txtChatInputActionPerformed(evt);
            }
        });
        pnlChatInput.add(txtChatInput, java.awt.BorderLayout.CENTER);

        btnSendChat.setBackground(new java.awt.Color(41, 128, 185));
        btnSendChat.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        btnSendChat.setForeground(new java.awt.Color(255, 255, 255));
        btnSendChat.setText("Gửi");
        btnSendChat.setFocusable(false);
        btnSendChat.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSendChatActionPerformed(evt);
            }
        });
        pnlChatInput.add(btnSendChat, java.awt.BorderLayout.EAST);

        pnlChat.add(pnlChatInput, java.awt.BorderLayout.SOUTH);

        splitRight.setRightComponent(pnlChat);

        splitMain.setRightComponent(splitRight);

        getContentPane().add(splitMain, java.awt.BorderLayout.CENTER);

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void btnNapkinActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnNapkinActionPerformed
        sendRingRequest("Xin thêm khăn ướt, tạp dề và dây buộc tóc");
    }//GEN-LAST:event_btnNapkinActionPerformed

    private void btnCallStaffActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnCallStaffActionPerformed
        // TODO add your handling code here:
        sendRingRequest("Cần nhân viên hỗ trợ trực tiếp tại bàn");
    }//GEN-LAST:event_btnCallStaffActionPerformed

    private void btnRefillBrothActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnRefillBrothActionPerformed
        // TODO add your handling code here:
        sendRingRequest("Yêu cầu châm thêm nước lẩu tại bàn");
    }//GEN-LAST:event_btnRefillBrothActionPerformed

    private void btnPayBillActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnPayBillActionPerformed
        // TODO add your handling code here:
        sendRingRequest("Yêu cầu kiểm đồ và thanh toán hóa đơn tại bàn");
    }//GEN-LAST:event_btnPayBillActionPerformed

    private void btnClearCartActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnClearCartActionPerformed
        // TODO add your handling code here:
        clearCart();
    }//GEN-LAST:event_btnClearCartActionPerformed

    private void btnSendOrderActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSendOrderActionPerformed
        // TODO add your handling code here:
        sendOrder();
    }//GEN-LAST:event_btnSendOrderActionPerformed

    private void btnSendChatActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSendChatActionPerformed
        // TODO add your handling code here:
        sendChatMessage();
    }//GEN-LAST:event_btnSendChatActionPerformed

    private void txtChatInputActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_txtChatInputActionPerformed
        // TODO add your handling code here:
        sendChatMessage();
    }//GEN-LAST:event_txtChatInputActionPerformed

    private void clearCart() {
        cartTableModel.setRowCount(0);
        currentTotalAmount = 0;
        updateTotalMoneyLabel();
    }

    private void sendOrder() {
        if (cartTableModel.getRowCount() == 0) {
            JOptionPane.showMessageDialog(this, "Quý khách vui lòng chọn ít nhất 1 món ăn trước khi gửi đơn!", "Giỏ hàng trống", JOptionPane.WARNING_MESSAGE);
            return;
        }

        List<String> summaryList = new ArrayList<>();
        for (int i = 0; i < cartTableModel.getRowCount(); i++) {
            summaryList.add(cartTableModel.getValueAt(i, 0) + " (x" + cartTableModel.getValueAt(i, 1) + ")");
        }
        String summary = String.join(", ", summaryList);

        if (client != null && client.isConnected()) {
            client.send(NetworkPacket.buildOrder(tableName, summary, currentTotalAmount));
            String moneyStr = new DecimalFormat("#,### VNĐ").format(currentTotalAmount);
            appendChat("🍲 [Bạn vừa đặt đơn]: " + summary + " - Tổng: " + moneyStr);

            cartTableModel.setRowCount(0);
            currentTotalAmount = 0;
            updateTotalMoneyLabel();
        } else {
            JOptionPane.showMessageDialog(this, "Không thể gửi đơn do chưa kết nối máy chủ! Vui lòng liên hệ nhân viên.", "Thông báo", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void sendChatMessage() {
        String msg = txtChatInput.getText().trim();
        if (msg.isEmpty()) return;

        if (client != null && client.isConnected()) {
            client.send(NetworkPacket.buildChatToReception(tableName, msg));
            appendChat("🧑‍💻 [Quý khách]: " + msg);
            txtChatInput.setText("");
        } else {
            JOptionPane.showMessageDialog(this, "Không thể gửi tin do chưa kết nối máy chủ! Vui lòng liên hệ nhân viên.", "Thông báo", JOptionPane.WARNING_MESSAGE);
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
            java.util.logging.Logger.getLogger(TabletClientFrame.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (InstantiationException ex) {
            java.util.logging.Logger.getLogger(TabletClientFrame.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (IllegalAccessException ex) {
            java.util.logging.Logger.getLogger(TabletClientFrame.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (javax.swing.UnsupportedLookAndFeelException ex) {
            java.util.logging.Logger.getLogger(TabletClientFrame.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        }
        //</editor-fold>

        /* Create and display the form */
        java.awt.EventQueue.invokeLater(new Runnable() {
            public void run() {
                // Hộp thoại khởi động hỗ trợ chọn hoặc gõ tên bàn bất kỳ và nhập IP Server qua mạng LAN
                javax.swing.JPanel panel = new javax.swing.JPanel(new java.awt.GridLayout(2, 2, 8, 8));
                javax.swing.JComboBox<String> cboTable = new javax.swing.JComboBox<>(new String[]{"Tablet_Ban1", "Tablet_Ban2", "Tablet_Ban3", "Tablet_Ban4", "Tablet_Ban5"});
                cboTable.setEditable(true); // Cho phép gõ thêm bàn mới như Tablet_Ban4, Tablet_Ban5...
                javax.swing.JTextField txtIp = new javax.swing.JTextField(ProtocolConstants.DEFAULT_SERVER_HOST);

                panel.add(new javax.swing.JLabel("Chọn / Nhập số bàn phục vụ:"));
                panel.add(cboTable);
                panel.add(new javax.swing.JLabel("Địa chỉ IP Máy Chủ (Server):"));
                panel.add(txtIp);

                int result = javax.swing.JOptionPane.showConfirmDialog(null, panel,
                        "Khởi động Tablet Khách Hàng (Haidilao Hotpot)",
                        javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.PLAIN_MESSAGE);

                if (result == javax.swing.JOptionPane.OK_OPTION) {
                    String picked = (cboTable.getSelectedItem() != null) ? cboTable.getSelectedItem().toString().trim() : "Tablet_Ban1";
                    String ip = txtIp.getText().trim();
                    if (ip.isEmpty()) ip = ProtocolConstants.DEFAULT_SERVER_HOST;
                    new TabletClientFrame(picked, ip).setVisible(true);
                }
            }
        });
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnCallStaff;
    private javax.swing.JButton btnClearCart;
    private javax.swing.JButton btnNapkin;
    private javax.swing.JButton btnPayBill;
    private javax.swing.JButton btnRefillBroth;
    private javax.swing.JButton btnSendChat;
    private javax.swing.JButton btnSendOrder;
    private javax.swing.JLabel lblHeaderStatus;
    private javax.swing.JLabel lblTotalMoney;
    private javax.swing.JPanel pnlCart;
    private javax.swing.JPanel pnlCartBottom;
    private javax.swing.JPanel pnlCartButtons;
    private javax.swing.JPanel pnlChat;
    private javax.swing.JPanel pnlChatInput;
    private javax.swing.JPanel pnlHeader;
    private javax.swing.JPanel pnlMenuCat1;
    private javax.swing.JPanel pnlMenuCat2;
    private javax.swing.JPanel pnlMenuCat3;
    private javax.swing.JPanel pnlMenuCat4;
    private javax.swing.JPanel pnlQuickService;
    private javax.swing.JScrollPane scrollCart;
    private javax.swing.JScrollPane scrollCat1;
    private javax.swing.JScrollPane scrollCat2;
    private javax.swing.JScrollPane scrollCat3;
    private javax.swing.JScrollPane scrollCat4;
    private javax.swing.JScrollPane scrollChat;
    private javax.swing.JSplitPane splitMain;
    private javax.swing.JSplitPane splitRight;
    private javax.swing.JTabbedPane tabMenuCategories;
    private javax.swing.JTable tblCart;
    private javax.swing.JTextArea txtChatHistory;
    private javax.swing.JTextField txtChatInput;
    // End of variables declaration//GEN-END:variables
}
