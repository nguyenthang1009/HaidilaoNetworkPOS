/*
 * WaiterFrame.java
 */
package com.haidilao.pos;

import com.haidilao.pos.client.ClientEventListener;
import com.haidilao.pos.client.PosClient;
import com.haidilao.pos.database.TableDao;
import com.haidilao.pos.model.Order;
import com.haidilao.pos.protocol.NetworkPacket;
import com.haidilao.pos.protocol.ProtocolConstants;
import com.haidilao.pos.ui.ChatPanel;
import com.haidilao.pos.ui.ResponsiveGrid;
import com.haidilao.pos.ui.TableCard;
import com.haidilao.pos.ui.Theme;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 * Màn hình Nhân viên phục vụ: các bàn Lễ tân giao cho mình, nhắn tin riêng với từng bàn, nhận chuông / yêu cầu thanh toán,
 * danh sách món cần mang ra (bếp nấu xong thì báo), đánh dấu đã mang ra.
 */
public class WaiterFrame extends javax.swing.JFrame implements ClientEventListener {

    private PosClient client;
    private LoginDialog pendingLogin;
    private ChatPanel chat;
    private DefaultTableModel serveModel;
    private boolean loading = false;
    private String selectedTable;
    // Các bàn đang được giao (TABLE_INFO đã giải mã), cảnh báo và số tin chưa đọc của từng bàn
    private final Map<String, String[]> myTables = new LinkedHashMap<>();
    private final Map<String, TableCard> cards = new LinkedHashMap<>();
    private final Map<String, String> alerts = new HashMap<>();
    private final Map<String, Integer> unread = new HashMap<>();
    private final List<String[]> receivingOrders = new ArrayList<>();

    public WaiterFrame() {
        initComponents();
        initCustomLogic();
    }

    private void initCustomLogic() {
        getContentPane().setBackground(Theme.BG);
        serveModel = (DefaultTableModel) tblServe.getModel();
        Theme.styleTable(tblServe, 4);
        int[] w = {40, 50, 60, 200, 90};
        for (int i = 0; i < w.length; i++) tblServe.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        chat = ChatPanel.installInto(pnlChatHost);
        ResponsiveGrid.install(scrollTables, pnlTableList, 240, 8);
        setChatEnabled(false);
        new Timer(1000, e -> lblClock.setText(new SimpleDateFormat("HH:mm:ss").format(new Date()))).start();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (client != null) client.disconnect();
            }
        });
        setMinimumSize(new Dimension(900, 600));
        Theme.fitToScreen(this, 0.9);
    }

    /** Đăng nhập (tài khoản Phục vụ hoặc Quản lý), tự tìm máy chủ trong mạng; đăng nhập xong mới hiện màn hình */
    public void startWithLogin() {
        LoginDialog dlg = new LoginDialog(null, "Đăng nhập Nhân viên phục vụ", (user, pass, d) -> {
            pendingLogin = d;
            if (client != null) client.disconnect();
            client = new PosClient(null, ProtocolConstants.SERVER_PORT, "WAITER", buildWaiterId(user), ProtocolConstants.KEY_WAITER, user, pass);
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

    /** Mỗi nhân viên một tên thiết bị riêng (PV_tàikhoản) để nhiều phục vụ dùng chung máy / nhiều máy không ghi đè nhau */
    private static String buildWaiterId(String user) {
        String id = "PV_" + user.replaceAll("[^A-Za-z0-9_-]", "");
        return id.length() > 30 ? id.substring(0, 30) : id;
    }

    private void send(String msg) {
        if (client != null && client.isConnected()) client.send(msg);
        else toast("Mất kết nối máy chủ, chưa gửi được.");
    }

    private void toast(String text) {
        Theme.flash(lblToast, text, 9000);
    }

    private String tableName(String tableId) {
        String[] info = myTables.get(tableId);
        return info != null ? info[2] : TableDao.defaultTableName(tableId);
    }

    // ------------------------------------------------------------------ danh sách bàn

    private void rebuildCards() {
        pnlTableList.removeAll();
        cards.keySet().retainAll(myTables.keySet());
        for (String id : myTables.keySet()) {
            TableCard card = cards.computeIfAbsent(id, k -> {
                TableCard c = new TableCard(k);
                c.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        selectTable(k);
                    }
                });
                return c;
            });
            pnlTableList.add(card);
            updateCard(id);
        }
        lblTablesHint.setText(myTables.isEmpty()
                ? "Chưa được giao bàn nào. Lễ tân sẽ phân công bàn cho bạn."
                : "Các bàn Lễ tân giao cho bạn. Bấm vào bàn để nhắn tin với khách.");
        lblCountTables.setText("Bàn phụ trách: " + myTables.size());
        ResponsiveGrid.refresh(pnlTableList);
        if (selectedTable != null && !myTables.containsKey(selectedTable)) {
            toast(tableName(selectedTable) + " không còn do bạn phụ trách.");
            selectedTable = null;
            chat.clear();
            lblChatTitle.setText("Chọn một bàn để nhắn tin");
            lblChatInfo.setText(" ");
            setChatEnabled(false);
        }
        refreshCounters();
    }

    private void updateCard(String tableId) {
        TableCard c = cards.get(tableId);
        String[] info = myTables.get(tableId);
        if (c == null || info == null) return;
        String alert = alerts.get(tableId);
        int n = unread.getOrDefault(tableId, 0);
        if (alert == null && n > 0) alert = "Tin nhắn mới (" + n + ")";
        c.update(info, alert);
    }

    private void setAlert(String tableId, String text) {
        if (text == null) alerts.remove(tableId); else alerts.put(tableId, text);
        updateCard(tableId);
        refreshCounters();
    }

    private void refreshCounters() {
        int done = 0;
        for (int r = 0; r < serveModel.getRowCount(); r++) {
            if (Order.STATUS_DONE.equals(serveModel.getValueAt(r, 4))) done++;
        }
        lblCountServe.setText("Món chờ mang ra: " + done + " / " + serveModel.getRowCount());
        long calls = alerts.values().stream().filter(a -> a.startsWith("Gọi") || a.startsWith("Yêu cầu")).count();
        lblCountCalls.setText("Đang gọi: " + calls);
    }

    private void selectTable(String tableId) {
        selectedTable = tableId;
        unread.remove(tableId);
        alerts.remove(tableId);
        updateCard(tableId);
        refreshCounters();
        String[] info = myTables.get(tableId);
        lblChatTitle.setText("Nhắn tin với " + tableName(tableId));
        lblChatInfo.setText(info == null ? " " : (info[7].isEmpty() ? "" : "Khách: " + info[7] + " · ") + info[8] + " khách");
        setChatEnabled(true);
        chat.clear();
        send(NetworkPacket.buildGetChat(tableId, "SESSION"));
        txtMessage.requestFocusInWindow();
    }

    private void setChatEnabled(boolean enabled) {
        txtMessage.setEnabled(enabled);
        btnSend.setEnabled(enabled);
        btnQuick1.setEnabled(enabled);
        btnQuick2.setEnabled(enabled);
        btnQuick3.setEnabled(enabled);
    }

    private void sendChat(String text) {
        String msg = text.trim();
        if (msg.isEmpty() || selectedTable == null) return;
        send(NetworkPacket.buildChatToTable(selectedTable, msg));
        chat.addMessage("Bạn", msg, new SimpleDateFormat("HH:mm").format(new Date()), true);
        txtMessage.setText("");
    }

    // ------------------------------------------------------------------ món cần mang ra

    /** NEW_ORDER / WAITER_ORDER|MÃ_ĐƠN|STT|BÀN|TÊN_BÀN|TÓM_TẮT|TỔNG|GHI_CHÚ|TRẠNG_THÁI|GIỜ */
    private void addServeRow(String[] f) {
        for (int r = 0; r < serveModel.getRowCount(); r++) {
            if (f[1].equals(String.valueOf(serveModel.getValueAt(r, 0)))) return;
        }
        serveModel.addRow(new Object[]{f[1], Order.formatOrderNo(NetworkPacket.parseInt(f[2], 0)), NetworkPacket.dec(f[4]),
                NetworkPacket.dec(f[5]), NetworkPacket.dec(f[8])});
    }

    private void markSelectedServed() {
        int row = tblServe.getSelectedRow();
        if (row < 0) {
            toast("Chọn một món trong bảng \"Món cần mang ra\" trước.");
            return;
        }
        String status = String.valueOf(serveModel.getValueAt(row, 4));
        if (!Order.STATUS_DONE.equals(status)
                && JOptionPane.showConfirmDialog(this, "Bếp chưa báo xong đơn này (" + status + "). Vẫn đánh dấu đã mang ra?",
                "Món chưa xong", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        String tableId = null;
        String name = String.valueOf(serveModel.getValueAt(row, 2));
        for (String[] i : myTables.values()) if (i[2].equals(name)) tableId = i[1];
        send(NetworkPacket.buildOrderStatusUpdate(Integer.parseInt(String.valueOf(serveModel.getValueAt(row, 0))),
                tableId == null ? "" : tableId, Order.STATUS_SERVED));
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
            setTitle("Phục vụ - " + client.getStaffName());
        });
        client.send(NetworkPacket.buildGetMyTables());
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
        SwingUtilities.invokeLater(() -> handleMessage(NetworkPacket.split(raw)));
    }

    private void handleMessage(String[] f) {
        switch (f[0]) {
            case "MY_TABLES_BEGIN":
                loading = true;
                myTables.clear();
                receivingOrders.clear();
                break;
            case "TABLE_INFO":
                if (loading && f.length >= 12) {
                    String[] info = new String[14];
                    for (int i = 0; i < info.length; i++) info[i] = i < f.length ? NetworkPacket.dec(f[i]) : "";
                    myTables.put(info[1], info);
                }
                break;
            case "WAITER_ORDER":
                if (loading && f.length >= 9) receivingOrders.add(f);
                break;
            case "MY_TABLES_END":
                loading = false;
                serveModel.setRowCount(0);
                for (String[] o : receivingOrders) addServeRow(o);
                receivingOrders.clear();
                rebuildCards();
                break;
            case "MY_TABLES_CHANGED":
                send(NetworkPacket.buildGetMyTables());
                break;
            case "CHAT_EVENT": {
                // CHAT_EVENT|BÀN|IN/OUT|TÊN_NGƯỜI_GỬI|GIỜ|NỘI_DUNG
                if (f.length < 6 || !myTables.containsKey(f[1])) return;
                boolean in = "IN".equals(f[2]);
                if (f[1].equals(selectedTable)) {
                    chat.addMessage(NetworkPacket.dec(f[3]), NetworkPacket.dec(f[5]), f[4], !in);
                } else if (in) {
                    unread.merge(f[1], 1, Integer::sum);
                    updateCard(f[1]);
                }
                if (in) {
                    Toolkit.getDefaultToolkit().beep();
                    toast(tableName(f[1]) + " nhắn: " + NetworkPacket.dec(f[5]));
                }
                break;
            }
            case "CHAT_HISTORY_BEGIN":
                if (f[1].equals(selectedTable)) chat.clear();
                break;
            case "CHAT_ITEM":
                // CHAT_ITEM|BÀN|IN/OUT|TÊN|dd/MM HH:mm|LOẠI|NỘI_DUNG
                if (f.length >= 7 && f[1].equals(selectedTable)) {
                    if ("RING".equals(f[5])) chat.addNotice(f[4] + " · khách bấm chuông: " + NetworkPacket.dec(f[6]));
                    else chat.addMessage(NetworkPacket.dec(f[3]), NetworkPacket.dec(f[6]), f[4], "OUT".equals(f[2]));
                }
                break;
            case "RING": {
                // RING|BÀN|LÝ_DO|PHỤC_VỤ
                if (!myTables.containsKey(f[1])) return;
                String reason = f.length > 2 ? f[2] : "Gọi nhân viên";
                setAlert(f[1], "Gọi: " + reason);
                Toolkit.getDefaultToolkit().beep();
                toast(tableName(f[1]) + " gọi: " + reason);
                if (f[1].equals(selectedTable)) chat.addNotice(new SimpleDateFormat("HH:mm").format(new Date()) + " · khách bấm chuông: " + reason);
                break;
            }
            case "PAY_REQUEST": {
                // PAY_REQUEST|BÀN|HÌNH_THỨC|TẠM_TÍNH
                if (!myTables.containsKey(f[1])) return;
                String method = NetworkPacket.dec(f[2]);
                setAlert(f[1], "Yêu cầu thanh toán · " + method);
                Toolkit.getDefaultToolkit().beep();
                toast(tableName(f[1]) + " muốn thanh toán (" + method + ", " + Theme.money(NetworkPacket.parseInt(f[3], 0)) + "). Báo Lễ tân / mang hóa đơn ra.");
                break;
            }
            case "NEW_ORDER":
                if (f.length >= 9 && myTables.containsKey(f[3])) {
                    addServeRow(f);
                    refreshCounters();
                    toast(tableName(f[3]) + " vừa gọi đơn " + Order.formatOrderNo(NetworkPacket.parseInt(f[2], 0)));
                }
                break;
            case "ORDER_UPDATED": {
                // ORDER_UPDATED|MÃ_ĐƠN|TRẠNG_THÁI|STT|BÀN
                for (int r = serveModel.getRowCount() - 1; r >= 0; r--) {
                    if (!f[1].equals(String.valueOf(serveModel.getValueAt(r, 0)))) continue;
                    if (Order.STATUS_SERVED.equals(f[2]) || Order.STATUS_CANCELLED.equals(f[2])) serveModel.removeRow(r);
                    else serveModel.setValueAt(f[2], r, 4);
                }
                if (Order.STATUS_DONE.equals(f[2]) && f.length > 4 && myTables.containsKey(f[4])) {
                    Toolkit.getDefaultToolkit().beep();
                    setAlert(f[4], "Món xong " + Order.formatOrderNo(NetworkPacket.parseInt(f[3], 0)) + " - mang ra");
                    toast("Bếp đã nấu xong đơn " + Order.formatOrderNo(NetworkPacket.parseInt(f[3], 0)) + " của " + tableName(f[4]) + " → mang ra cho khách.");
                } else if (Order.STATUS_SERVED.equals(f[2]) && f.length > 4) {
                    String a = alerts.get(f[4]);
                    if (a != null && a.startsWith("Món xong")) setAlert(f[4], null);
                }
                refreshCounters();
                break;
            }
            case "SERVER_ACK":
                toast(f.length > 1 ? f[1] : "");
                break;
            default:
                break;
        }
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
        lblCountTables = new javax.swing.JLabel();
        lblCountServe = new javax.swing.JLabel();
        lblCountCalls = new javax.swing.JLabel();
        pnlHeaderRight = new javax.swing.JPanel();
        lblConnection = new javax.swing.JLabel();
        lblClock = new javax.swing.JLabel();
        splitMain = new javax.swing.JSplitPane();
        pnlLeft = new javax.swing.JPanel();
        lblTablesHint = new javax.swing.JLabel();
        scrollTables = new javax.swing.JScrollPane();
        pnlTableList = new javax.swing.JPanel();
        pnlServe = new javax.swing.JPanel();
        scrollServe = new javax.swing.JScrollPane();
        tblServe = new javax.swing.JTable();
        pnlServeButtons = new javax.swing.JPanel();
        lblServeHint = new javax.swing.JLabel();
        btnServed = new javax.swing.JButton();
        pnlConversation = new javax.swing.JPanel();
        pnlChatHeader = new javax.swing.JPanel();
        lblChatTitle = new javax.swing.JLabel();
        lblChatInfo = new javax.swing.JLabel();
        pnlChatHost = new javax.swing.JPanel();
        pnlChatBottom = new javax.swing.JPanel();
        pnlQuickReplies = new javax.swing.JPanel();
        btnQuick1 = new javax.swing.JButton();
        btnQuick2 = new javax.swing.JButton();
        btnQuick3 = new javax.swing.JButton();
        pnlChatInput = new javax.swing.JPanel();
        txtMessage = new javax.swing.JTextField();
        btnSend = new javax.swing.JButton();
        pnlStatusBar = new javax.swing.JPanel();
        lblToast = new javax.swing.JLabel();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("Nhân viên phục vụ");
        getContentPane().setLayout(new java.awt.BorderLayout());

        pnlHeader.setBackground(new java.awt.Color(31, 36, 48));
        pnlHeader.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 16, 10, 16));
        pnlHeader.setLayout(new java.awt.BorderLayout(16, 0));

        pnlHeaderLeft.setOpaque(false);
        pnlHeaderLeft.setLayout(new java.awt.GridLayout(2, 1, 0, 0));

        lblTitle.setFont(new java.awt.Font("Segoe UI", 1, 18)); // NOI18N
        lblTitle.setForeground(new java.awt.Color(241, 196, 15));
        lblTitle.setText("NHÂN VIÊN PHỤC VỤ");
        pnlHeaderLeft.add(lblTitle);

        lblStaff.setFont(new java.awt.Font("Segoe UI", 0, 12)); // NOI18N
        lblStaff.setForeground(new java.awt.Color(200, 206, 216));
        lblStaff.setText("Nhân viên: -");
        pnlHeaderLeft.add(lblStaff);

        pnlHeader.add(pnlHeaderLeft, java.awt.BorderLayout.WEST);

        pnlCounters.setOpaque(false);
        pnlCounters.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 6));

        lblCountTables.setBackground(new java.awt.Color(44, 52, 68));
        lblCountTables.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblCountTables.setForeground(new java.awt.Color(255, 255, 255));
        lblCountTables.setText("Bàn phụ trách: 0");
        lblCountTables.setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 12, 5, 12));
        lblCountTables.setOpaque(true);
        pnlCounters.add(lblCountTables);

        lblCountServe.setBackground(new java.awt.Color(44, 52, 68));
        lblCountServe.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblCountServe.setForeground(new java.awt.Color(130, 224, 170));
        lblCountServe.setText("Món chờ mang ra: 0");
        lblCountServe.setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 12, 5, 12));
        lblCountServe.setOpaque(true);
        pnlCounters.add(lblCountServe);

        lblCountCalls.setBackground(new java.awt.Color(44, 52, 68));
        lblCountCalls.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblCountCalls.setForeground(new java.awt.Color(241, 148, 138));
        lblCountCalls.setText("Đang gọi: 0");
        lblCountCalls.setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 12, 5, 12));
        lblCountCalls.setOpaque(true);
        pnlCounters.add(lblCountCalls);

        pnlHeader.add(pnlCounters, java.awt.BorderLayout.CENTER);

        pnlHeaderRight.setOpaque(false);
        pnlHeaderRight.setLayout(new java.awt.GridLayout(2, 1, 0, 0));

        lblConnection.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        lblConnection.setForeground(new java.awt.Color(241, 196, 15));
        lblConnection.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        lblConnection.setText("● Đang tìm máy chủ...");
        pnlHeaderRight.add(lblConnection);

        lblClock.setFont(new java.awt.Font("Segoe UI", 0, 12)); // NOI18N
        lblClock.setForeground(new java.awt.Color(200, 206, 216));
        lblClock.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        lblClock.setText("--:--");
        pnlHeaderRight.add(lblClock);

        pnlHeader.add(pnlHeaderRight, java.awt.BorderLayout.EAST);

        getContentPane().add(pnlHeader, java.awt.BorderLayout.NORTH);

        splitMain.setDividerLocation(470);
        splitMain.setResizeWeight(0.42);
        pnlLeft.setBackground(new java.awt.Color(244, 245, 247));
        pnlLeft.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 5));
        pnlLeft.setLayout(new java.awt.BorderLayout(0, 8));

        lblTablesHint.setFont(new java.awt.Font("Segoe UI", 0, 12)); // NOI18N
        lblTablesHint.setForeground(new java.awt.Color(127, 140, 141));
        lblTablesHint.setText("Các bàn Lễ tân giao cho bạn. Bấm vào bàn để nhắn tin với khách.");
        pnlLeft.add(lblTablesHint, java.awt.BorderLayout.NORTH);

        pnlTableList.setBackground(new java.awt.Color(244, 245, 247));
        pnlTableList.setLayout(new java.awt.GridLayout(0, 1, 8, 8));

        scrollTables.setViewportView(pnlTableList);

        pnlLeft.add(scrollTables, java.awt.BorderLayout.CENTER);

        pnlServe.setBackground(new java.awt.Color(255, 255, 255));
        pnlServe.setBorder(javax.swing.BorderFactory.createTitledBorder(null, "Món cần mang ra", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Segoe UI", 1, 12), new java.awt.Color(44, 62, 80))); // NOI18N
        pnlServe.setPreferredSize(new java.awt.Dimension(300, 250));
        pnlServe.setLayout(new java.awt.BorderLayout(0, 6));

        tblServe.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        tblServe.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {

            },
            new String [] {
                "Mã", "STT", "Bàn", "Món", "Trạng thái"
            }
        ) {
            boolean[] canEdit = new boolean [] {
                false, false, false, false, false
            };

            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return canEdit [columnIndex];
            }
        });
        tblServe.setRowHeight(28);
        scrollServe.setViewportView(tblServe);

        pnlServe.add(scrollServe, java.awt.BorderLayout.CENTER);

        pnlServeButtons.setOpaque(false);
        pnlServeButtons.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));

        lblServeHint.setFont(new java.awt.Font("Segoe UI", 0, 11)); // NOI18N
        lblServeHint.setForeground(new java.awt.Color(127, 140, 141));
        lblServeHint.setText("Món xong hiện màu xanh, mang ra rồi bấm nút");
        pnlServeButtons.add(lblServeHint);

        btnServed.setBackground(new java.awt.Color(39, 174, 96));
        btnServed.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnServed.setForeground(new java.awt.Color(255, 255, 255));
        btnServed.setText("Đã mang món ra");
        btnServed.setFocusable(false);
        btnServed.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnServedActionPerformed(evt);
            }
        });
        pnlServeButtons.add(btnServed);

        pnlServe.add(pnlServeButtons, java.awt.BorderLayout.SOUTH);

        pnlLeft.add(pnlServe, java.awt.BorderLayout.SOUTH);

        splitMain.setLeftComponent(pnlLeft);

        pnlConversation.setBackground(new java.awt.Color(244, 245, 247));
        pnlConversation.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 5, 10, 10));
        pnlConversation.setLayout(new java.awt.BorderLayout(0, 8));

        pnlChatHeader.setBackground(new java.awt.Color(255, 255, 255));
        pnlChatHeader.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 12, 8, 12));
        pnlChatHeader.setLayout(new java.awt.BorderLayout(8, 0));

        lblChatTitle.setFont(new java.awt.Font("Segoe UI", 1, 15)); // NOI18N
        lblChatTitle.setForeground(new java.awt.Color(44, 62, 80));
        lblChatTitle.setText("Chọn một bàn để nhắn tin");
        pnlChatHeader.add(lblChatTitle, java.awt.BorderLayout.WEST);

        lblChatInfo.setFont(new java.awt.Font("Segoe UI", 0, 12)); // NOI18N
        lblChatInfo.setForeground(new java.awt.Color(127, 140, 141));
        lblChatInfo.setText(" ");
        pnlChatHeader.add(lblChatInfo, java.awt.BorderLayout.EAST);

        pnlConversation.add(pnlChatHeader, java.awt.BorderLayout.NORTH);

        pnlChatHost.setBackground(new java.awt.Color(255, 255, 255));
        pnlChatHost.setLayout(new java.awt.BorderLayout());

        pnlConversation.add(pnlChatHost, java.awt.BorderLayout.CENTER);

        pnlChatBottom.setOpaque(false);
        pnlChatBottom.setLayout(new java.awt.BorderLayout(0, 6));

        pnlQuickReplies.setOpaque(false);
        pnlQuickReplies.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));

        btnQuick1.setBackground(new java.awt.Color(236, 240, 241));
        btnQuick1.setFont(new java.awt.Font("Segoe UI", 0, 12)); // NOI18N
        btnQuick1.setForeground(new java.awt.Color(44, 62, 80));
        btnQuick1.setText("Dạ, em đến ngay ạ");
        btnQuick1.setFocusable(false);
        btnQuick1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnQuick1ActionPerformed(evt);
            }
        });
        pnlQuickReplies.add(btnQuick1);

        btnQuick2.setBackground(new java.awt.Color(236, 240, 241));
        btnQuick2.setFont(new java.awt.Font("Segoe UI", 0, 12)); // NOI18N
        btnQuick2.setForeground(new java.awt.Color(44, 62, 80));
        btnQuick2.setText("Món đang được chuẩn bị ạ");
        btnQuick2.setFocusable(false);
        btnQuick2.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnQuick2ActionPerformed(evt);
            }
        });
        pnlQuickReplies.add(btnQuick2);

        btnQuick3.setBackground(new java.awt.Color(236, 240, 241));
        btnQuick3.setFont(new java.awt.Font("Segoe UI", 0, 12)); // NOI18N
        btnQuick3.setForeground(new java.awt.Color(44, 62, 80));
        btnQuick3.setText("Dạ vâng, em mang ra liền");
        btnQuick3.setFocusable(false);
        btnQuick3.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnQuick3ActionPerformed(evt);
            }
        });
        pnlQuickReplies.add(btnQuick3);

        pnlChatBottom.add(pnlQuickReplies, java.awt.BorderLayout.NORTH);

        pnlChatInput.setOpaque(false);
        pnlChatInput.setLayout(new java.awt.BorderLayout(6, 0));

        txtMessage.setFont(new java.awt.Font("Segoe UI", 0, 14)); // NOI18N
        txtMessage.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                txtMessageActionPerformed(evt);
            }
        });
        pnlChatInput.add(txtMessage, java.awt.BorderLayout.CENTER);

        btnSend.setBackground(new java.awt.Color(169, 29, 34));
        btnSend.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        btnSend.setForeground(new java.awt.Color(255, 255, 255));
        btnSend.setText("Gửi");
        btnSend.setFocusable(false);
        btnSend.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSendActionPerformed(evt);
            }
        });
        pnlChatInput.add(btnSend, java.awt.BorderLayout.EAST);

        pnlChatBottom.add(pnlChatInput, java.awt.BorderLayout.CENTER);

        pnlConversation.add(pnlChatBottom, java.awt.BorderLayout.SOUTH);

        splitMain.setRightComponent(pnlConversation);

        getContentPane().add(splitMain, java.awt.BorderLayout.CENTER);

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

    private void btnServedActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnServedActionPerformed
        markSelectedServed();
    }//GEN-LAST:event_btnServedActionPerformed

    private void btnQuick1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnQuick1ActionPerformed
        sendChat(btnQuick1.getText());
    }//GEN-LAST:event_btnQuick1ActionPerformed

    private void btnQuick2ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnQuick2ActionPerformed
        sendChat(btnQuick2.getText());
    }//GEN-LAST:event_btnQuick2ActionPerformed

    private void btnQuick3ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnQuick3ActionPerformed
        sendChat(btnQuick3.getText());
    }//GEN-LAST:event_btnQuick3ActionPerformed

    private void txtMessageActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_txtMessageActionPerformed
        sendChat(txtMessage.getText());
    }//GEN-LAST:event_txtMessageActionPerformed

    private void btnSendActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSendActionPerformed
        sendChat(txtMessage.getText());
    }//GEN-LAST:event_btnSendActionPerformed

    public static void main(String args[]) {
        HaidilaoNetworkPOS.applyLookAndFeel();
        java.awt.EventQueue.invokeLater(() -> new WaiterFrame().startWithLogin());
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JPanel pnlHeader;
    private javax.swing.JPanel pnlHeaderLeft;
    private javax.swing.JLabel lblTitle;
    private javax.swing.JLabel lblStaff;
    private javax.swing.JPanel pnlCounters;
    private javax.swing.JLabel lblCountTables;
    private javax.swing.JLabel lblCountServe;
    private javax.swing.JLabel lblCountCalls;
    private javax.swing.JPanel pnlHeaderRight;
    private javax.swing.JLabel lblConnection;
    private javax.swing.JLabel lblClock;
    private javax.swing.JSplitPane splitMain;
    private javax.swing.JPanel pnlLeft;
    private javax.swing.JLabel lblTablesHint;
    private javax.swing.JScrollPane scrollTables;
    private javax.swing.JPanel pnlTableList;
    private javax.swing.JPanel pnlServe;
    private javax.swing.JScrollPane scrollServe;
    private javax.swing.JTable tblServe;
    private javax.swing.JPanel pnlServeButtons;
    private javax.swing.JLabel lblServeHint;
    private javax.swing.JButton btnServed;
    private javax.swing.JPanel pnlConversation;
    private javax.swing.JPanel pnlChatHeader;
    private javax.swing.JLabel lblChatTitle;
    private javax.swing.JLabel lblChatInfo;
    private javax.swing.JPanel pnlChatHost;
    private javax.swing.JPanel pnlChatBottom;
    private javax.swing.JPanel pnlQuickReplies;
    private javax.swing.JButton btnQuick1;
    private javax.swing.JButton btnQuick2;
    private javax.swing.JButton btnQuick3;
    private javax.swing.JPanel pnlChatInput;
    private javax.swing.JTextField txtMessage;
    private javax.swing.JButton btnSend;
    private javax.swing.JPanel pnlStatusBar;
    private javax.swing.JLabel lblToast;
    // End of variables declaration//GEN-END:variables
}
