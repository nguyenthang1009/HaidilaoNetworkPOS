/*
 * ReceptionFrame.java
 */
package com.haidilao.pos;

import com.haidilao.pos.client.ClientEventListener;
import com.haidilao.pos.client.MessageListener;
import com.haidilao.pos.client.PosClient;
import com.haidilao.pos.database.TableDao;
import com.haidilao.pos.model.DiningTable;
import com.haidilao.pos.model.Order;
import com.haidilao.pos.model.Reservation;
import com.haidilao.pos.protocol.NetworkPacket;
import com.haidilao.pos.protocol.ProtocolConstants;
import com.haidilao.pos.ui.ChatPanel;
import com.haidilao.pos.ui.ResponsiveGrid;
import com.haidilao.pos.ui.TableCard;
import com.haidilao.pos.ui.Theme;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Màn hình Quầy Lễ tân: tổng hợp chỗ ngồi, sơ đồ bàn theo khu vực (bấm vào bàn để xem chi tiết),
 * đơn hàng có lọc theo bàn / trạng thái, đặt bàn trước, tin nhắn với các bàn.
 */
public class ReceptionFrame extends javax.swing.JFrame implements ClientEventListener {

    private PosClient client;
    private LoginDialog pendingLogin;          // hộp đăng nhập đang chờ kết quả
    private ChatPanel chat;
    private DefaultTableModel ordersModel;
    private DefaultTableModel resModel;
    private TableRowSorter<DefaultTableModel> orderSorter;
    // Thông tin mới nhất của từng bàn (TABLE_INFO đã giải mã), giữ thứ tự Server gửi
    private final Map<String, String[]> tableInfos = new LinkedHashMap<>();
    private final Map<String, TableCard> cards = new LinkedHashMap<>();
    private final Map<String, String> alerts = new HashMap<>();          // bàn -> cảnh báo (chuông, thanh toán, đơn mới)
    private final Map<Integer, String> waiters = new LinkedHashMap<>();   // mã -> tên nhân viên phục vụ
    private final List<String[]> reservations = new ArrayList<>();       // RESERVATION đã giải mã của ngày đang xem
    private final List<MessageListener> listeners = new CopyOnWriteArrayList<>();
    private boolean loadingTables = false;
    // Tin nhắn: mỗi bàn một cuộc trò chuyện riêng
    private DefaultTableModel convModel;
    private final List<String> convRows = new ArrayList<>();               // mã bàn theo thứ tự dòng của bảng cuộc trò chuyện
    private final Map<String, String[]> conversations = new HashMap<>();   // bàn -> {giờ tin cuối, nội dung tin cuối, khóa sắp xếp}
    private final Map<String, Integer> chatUnread = new HashMap<>();
    private final Map<Integer, int[]> waiterLoad = new HashMap<>();        // mã phục vụ -> {đang trực, số bàn}
    private String chatTable;                                             // bàn đang mở cuộc trò chuyện
    private boolean fillingConv = false;
    private int convLoadIndex = 0;
    private java.util.Date resDate = new java.util.Date();
    private static final SimpleDateFormat DAY = new SimpleDateFormat("dd/MM/yyyy");
    private static final SimpleDateFormat ISO_DAY = new SimpleDateFormat("yyyy-MM-dd");

    public ReceptionFrame() {
        initComponents();
        initCustomLogic();
    }

    private void initCustomLogic() {
        getContentPane().setBackground(Theme.BG);
        ordersModel = (DefaultTableModel) tblOrders.getModel();
        resModel = (DefaultTableModel) tblReservations.getModel();
        Theme.styleTable(tblOrders, 6);
        Theme.styleTable(tblReservations, 8);
        int[] ow = {50, 50, 70, 50, 260, 90, 100, 140};
        for (int i = 0; i < ow.length; i++) tblOrders.getColumnModel().getColumn(i).setPreferredWidth(ow[i]);
        int[] rw = {45, 55, 130, 100, 65, 70, 80, 150, 100};
        for (int i = 0; i < rw.length; i++) tblReservations.getColumnModel().getColumn(i).setPreferredWidth(rw[i]);
        orderSorter = new TableRowSorter<>(ordersModel);
        tblOrders.setRowSorter(orderSorter);
        applyOrderFilter();
        tabRight.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        chat = ChatPanel.installInto(pnlChatHost);
        convModel = (DefaultTableModel) tblConversations.getModel();
        Theme.styleTable(tblConversations, -1);
        tblConversations.getColumnModel().getColumn(0).setPreferredWidth(75);
        tblConversations.getColumnModel().getColumn(1).setPreferredWidth(115);
        tblConversations.getColumnModel().getColumn(2).setPreferredWidth(55);
        tblConversations.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || fillingConv) return;
            int row = tblConversations.getSelectedRow();
            if (row >= 0 && row < convRows.size()) openConversation(convRows.get(row));
        });
        setReplyEnabled(false);
        ResponsiveGrid.install(scrollMap, pnlTableMap, 210, 10);
        txtResDate.setText(DAY.format(resDate));
        tblOrders.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) openSelectedOrderTable();
            }
        });
        tblReservations.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) editSelectedReservation();
            }
        });
        tabRight.addChangeListener(e -> {
            if (tabRight.getSelectedComponent() == splitMessages && chatTable != null) {
                chatUnread.remove(chatTable);
                refreshConversationList();
            }
        });
        new javax.swing.Timer(1000, e -> lblClock.setText(new SimpleDateFormat("HH:mm:ss  ·  dd/MM/yyyy").format(new java.util.Date()))).start();
        // Trạng thái "ĐÃ ĐẶT" phụ thuộc giờ hiện tại nên tải lại sơ đồ bàn mỗi phút
        new javax.swing.Timer(60_000, e -> requestTables()).start();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (client != null) client.disconnect();
            }
        });
        setMinimumSize(new Dimension(1100, 680));
        Theme.fitToScreen(this, 0.94);
    }

    // ------------------------------------------------------------------ đăng nhập + kết nối

    /** Hiện hộp đăng nhập, kết nối máy chủ (tự tìm trong mạng). Đăng nhập xong mới hiện màn hình Lễ tân. */
    public void startWithLogin() {
        LoginDialog dlg = new LoginDialog(null, "Đăng nhập Quầy Lễ tân", (user, pass, d) -> {
            pendingLogin = d;
            if (client != null) client.disconnect();
            client = new PosClient(null, ProtocolConstants.SERVER_PORT, "RECEPTION", buildReceptionId(), ProtocolConstants.KEY_RECEPTION, user, pass);
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

    /** Mỗi máy Lễ tân dùng tên riêng theo tên máy tính để nhiều quầy cùng kết nối không ghi đè nhau */
    private static String buildReceptionId() {
        String host = "";
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {}
        host = host.replaceAll("[^A-Za-z0-9_-]", "").toUpperCase();
        String id = host.isEmpty() ? "PC_LeTan" : "PC_LeTan_" + host;
        return id.length() > 30 ? id.substring(0, 30) : id;
    }

    public PosClient getClient() {
        return client;
    }

    public Map<Integer, String> getWaiters() {
        return waiters;
    }

    public Collection<String[]> getTableInfos() {
        return tableInfos.values();
    }

    public String[] getTableInfo(String tableId) {
        return tableInfos.get(tableId);
    }

    public String tableName(String tableId) {
        String[] info = tableInfos.get(tableId);
        return info != null ? info[2] : TableDao.defaultTableName(tableId);
    }

    public void addListener(MessageListener l) {
        listeners.add(l);
    }

    public void removeListener(MessageListener l) {
        listeners.remove(l);
    }

    public void send(String msg) {
        if (client != null && client.isConnected()) {
            client.send(msg);
        } else {
            toast("Chưa kết nối máy chủ, thao tác chưa được gửi.");
        }
    }

    public void toast(String text) {
        Theme.flash(lblToast, text, 9000);
    }

    private void requestTables() {
        if (client != null && client.isConnected()) client.send(NetworkPacket.buildGetTables());
    }

    private void requestReservations() {
        if (client != null && client.isConnected()) client.send(NetworkPacket.buildGetReservations(ISO_DAY.format(resDate)));
    }

    // ------------------------------------------------------------------ sơ đồ bàn

    private void rebuildMap() {
        String area = (String) cboArea.getSelectedItem();
        pnlTableMap.removeAll();
        for (String[] info : tableInfos.values()) {
            if (area != null && !area.startsWith("Tất cả") && !area.equals(info[4])) continue;
            pnlTableMap.add(cardFor(info[1]));
        }
        ResponsiveGrid.refresh(pnlTableMap);
    }

    private TableCard cardFor(String tableId) {
        return cards.computeIfAbsent(tableId, id -> {
            TableCard c = new TableCard(id);
            c.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isRightMouseButton(e)) showTableMenu(c, e.getX(), e.getY());
                    else openTableDetail(id);
                }
            });
            String[] info = tableInfos.get(id);
            if (info != null) c.update(info, alerts.get(id));
            return c;
        });
    }

    private void updateCard(String tableId) {
        String[] info = tableInfos.get(tableId);
        TableCard c = cards.get(tableId);
        if (info != null && c != null) c.update(info, alerts.get(tableId));
    }

    private void setAlert(String tableId, String text) {
        if (text == null) alerts.remove(tableId); else alerts.put(tableId, text);
        updateCard(tableId);
    }

    /** Lễ tân đã mở chi tiết bàn: coi như đã thấy cảnh báo */
    public void clearAlert(String tableId) {
        setAlert(tableId, null);
    }

    private void refreshAreaAndTableLists() {
        Set<String> areas = new TreeSet<>();
        for (String[] i : tableInfos.values()) areas.add(i[4]);
        String selArea = (String) cboArea.getSelectedItem();
        cboArea.removeAllItems();
        cboArea.addItem("Tất cả khu vực");
        for (String a : areas) cboArea.addItem(a);
        if (selArea != null) cboArea.setSelectedItem(selArea);

        String selFilter = (String) cboTableFilter.getSelectedItem();
        cboTableFilter.removeAllItems();
        cboTableFilter.addItem("Tất cả các bàn");
        for (String[] i : tableInfos.values()) {
            cboTableFilter.addItem(i[2]);
        }
        if (selFilter != null) cboTableFilter.setSelectedItem(selFilter);
        refreshConversationList();
    }

    private String tableIdByName(String name) {
        for (String[] i : tableInfos.values()) if (i[2].equals(name)) return i[1];
        return null;
    }

    /** Thanh tổng hợp chỗ ngồi trên đầu màn hình */
    private void refreshStats() {
        int tables = 0, free = 0, seats = 0, freeSeats = 0, guests = 0, cleaning = 0;
        for (String[] i : tableInfos.values()) {
            int cap = NetworkPacket.parseInt(i[3], 0);
            tables++;
            seats += cap;
            if (DiningTable.STATUS_EMPTY.equals(i[5])) {
                free++;
                freeSeats += cap;
            }
            if (DiningTable.STATUS_CLEANING.equals(i[5])) cleaning++;
            if (!"0".equals(i[6])) guests += NetworkPacket.parseInt(i[8], 0);
        }
        lblStatFreeTables.setText("Bàn trống: " + free + "/" + tables);
        lblStatFreeSeats.setText("Ghế trống: " + freeSeats + "/" + seats);
        lblStatGuests.setText("Khách đang ngồi: " + guests);
        lblStatCleaning.setText("Cần dọn: " + cleaning);
    }

    private void showTableMenu(TableCard card, int x, int y) {
        String id = card.getTableId();
        String[] info = tableInfos.get(id);
        boolean serving = info != null && !"0".equals(info[6]);
        JPopupMenu m = new JPopupMenu();
        addMenu(m, "Xem chi tiết / thông tin khách", () -> openTableDetail(id));
        if (serving) {
            addMenu(m, "Thanh toán", () -> openCheckout(id));
            addMenu(m, "Chuyển sang bàn khác", () -> moveTable(id));
            addMenu(m, "Hủy bàn (khách về, chưa gọi món)", () -> cancelTable(id));
        } else {
            addMenu(m, "Mở bàn cho khách", () -> openTableDetail(id));
            addMenu(m, "Đặt bàn này", () -> openReservation(null, id));
            if (info != null && DiningTable.STATUS_CLEANING.equals(info[5])) {
                addMenu(m, "Đã dọn xong", () -> send(NetworkPacket.buildSetTableStatus(id, DiningTable.STATUS_EMPTY)));
            }
        }
        addMenu(m, "Nhắn tin với bàn", () -> {
            tabRight.setSelectedComponent(splitMessages);
            openConversation(id);
        });
        if (alerts.containsKey(id)) addMenu(m, "Đã xử lý yêu cầu (tắt cảnh báo)", () -> clearAlert(id));
        m.show(card, x, y);
    }

    private static void addMenu(JPopupMenu m, String text, Runnable r) {
        JMenuItem i = new JMenuItem(text);
        i.setFont(Theme.FONT);
        i.addActionListener(e -> r.run());
        m.add(i);
    }

    // ------------------------------------------------------------------ mở các hộp thoại

    public void openTableDetail(String tableId) {
        clearAlert(tableId);
        for (MessageListener l : listeners) {
            if (l instanceof TableDetailDialog && ((TableDetailDialog) l).getTableId().equals(tableId)) {
                ((TableDetailDialog) l).toFront();
                return;
            }
        }
        new TableDetailDialog(this, tableId).setVisible(true);
    }

    public void openCheckout(String tableId) {
        String[] info = tableInfos.get(tableId);
        if (info == null || "0".equals(info[6])) {
            toast(tableName(tableId) + " chưa có khách, không có gì để thanh toán.");
            return;
        }
        clearAlert(tableId);
        new CheckoutDialog(this, tableId, info.length > 13 ? info[13] : "").setVisible(true);
    }

    public void moveTable(String fromTable) {
        List<String> names = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        String[] from = tableInfos.get(fromTable);
        int guests = from == null ? 0 : NetworkPacket.parseInt(from[8], 0);
        for (String[] i : tableInfos.values()) {
            if ("0".equals(i[6]) && !i[1].equals(fromTable) && !DiningTable.STATUS_CLEANING.equals(i[5])
                    && NetworkPacket.parseInt(i[3], 0) >= guests) {
                ids.add(i[1]);
                names.add(i[2] + " · " + i[3] + " ghế · " + i[4] + (DiningTable.STATUS_EMPTY.equals(i[5]) ? "" : " (" + i[5] + ")"));
            }
        }
        if (ids.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Hiện không còn bàn trống (đã dọn) đủ " + guests + " ghế để chuyển.", "Chuyển bàn", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Object pick = JOptionPane.showInputDialog(this, "Chuyển khách của " + tableName(fromTable) + " sang bàn:", "Chuyển bàn",
                JOptionPane.QUESTION_MESSAGE, null, names.toArray(), names.get(0));
        if (pick != null) send(NetworkPacket.buildMoveTable(fromTable, ids.get(names.indexOf(pick))));
    }

    /**
     * Hủy bàn khi khách về mà chưa gọi món. Server từ chối nếu bàn đã có món (bàn có món bắt buộc thanh toán).
     */
    public void cancelTable(String tableId) {
        String[] info = tableInfos.get(tableId);
        if (info == null || "0".equals(info[6])) {
            toast(tableName(tableId) + " đang không có khách.");
            return;
        }
        Object reason = JOptionPane.showInputDialog(this,
                "Hủy bàn " + tableName(tableId) + " (khách về, chưa gọi món)?\nBàn đã gọi món thì phải thanh toán, không hủy được."
                + "\nNếu khách đã đặt cọc, nhà hàng giữ lại tiền cọc.\n\nLý do:",
                "Hủy bàn", JOptionPane.QUESTION_MESSAGE, null,
                new String[]{"Khách về, không dùng bữa", "Khách đổi ý / chờ lâu", "Mở nhầm bàn", "Khác"}, "Khách về, không dùng bữa");
        if (reason != null) send(NetworkPacket.buildCancelSession(tableId, reason.toString()));
    }

    public void openReservation(String[] existing, String presetTable) {
        new ReservationDialog(this, existing, presetTable, resDate).setVisible(true);
    }

    private void openSelectedOrderTable() {
        int row = tblOrders.getSelectedRow();
        if (row < 0) {
            toast("Chọn một đơn trong bảng trước.");
            return;
        }
        String id = tableIdByName(String.valueOf(tblOrders.getValueAt(row, 2)));
        if (id != null) openTableDetail(id);
    }

    // ------------------------------------------------------------------ đơn hàng

    private void applyOrderFilter() {
        String table = (String) cboTableFilter.getSelectedItem();
        String status = (String) cboStatusFilter.getSelectedItem();
        List<RowFilter<DefaultTableModel, Integer>> filters = new ArrayList<>();
        if (table != null && !table.startsWith("Tất cả")) filters.add(RowFilter.regexFilter("^" + Pattern.quote(table) + "$", 2));
        if ("Chưa phục vụ".equals(status)) {
            filters.add(RowFilter.regexFilter("^(" + Order.STATUS_WAITING + "|" + Order.STATUS_COOKING + "|" + Order.STATUS_DONE + ")$", 6));
        } else if (status != null && !"Tất cả".equals(status)) {
            filters.add(RowFilter.regexFilter("^" + Pattern.quote(status) + "$", 6));
        }
        orderSorter.setRowFilter(filters.isEmpty() ? null : RowFilter.andFilter(filters));
    }

    /** NEW_ORDER / OLD_ORDER|MÃ_ĐƠN|STT|BÀN|TÊN_BÀN|TÓM_TẮT|TỔNG_TIỀN|GHI_CHÚ|TRẠNG_THÁI|GIỜ */
    private void addOrderRow(String[] f) {
        ordersModel.insertRow(0, new Object[]{f[1], Order.formatOrderNo(NetworkPacket.parseInt(f[2], 0)), NetworkPacket.dec(f[4]),
                f.length > 9 ? f[9] : "", NetworkPacket.dec(f[5]), Theme.money(NetworkPacket.parseInt(f[6], 0)),
                NetworkPacket.dec(f[8]), NetworkPacket.dec(f[7])});
    }

    private void updateSelectedOrder(String newStatus) {
        int row = tblOrders.getSelectedRow();
        if (row < 0) {
            toast("Chọn một đơn trong bảng trước.");
            return;
        }
        int orderId = Integer.parseInt(String.valueOf(tblOrders.getValueAt(row, 0)));
        String current = String.valueOf(tblOrders.getValueAt(row, 6));
        String tableId = tableIdByName(String.valueOf(tblOrders.getValueAt(row, 2)));
        if (Order.STATUS_SERVED.equals(current) || Order.STATUS_CANCELLED.equals(current)) {
            toast("Đơn này đã " + current.toLowerCase() + ", không đổi được nữa.");
            return;
        }
        if (Order.STATUS_CANCELLED.equals(newStatus)) {
            int ok = JOptionPane.showConfirmDialog(this, "Hủy đơn " + tblOrders.getValueAt(row, 1) + " của " + tblOrders.getValueAt(row, 2)
                    + "?\nĐơn đã hủy không được tính tiền.", "Xác nhận hủy đơn", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ok != JOptionPane.YES_OPTION) return;
        }
        // Bảng chỉ đổi khi Server xác nhận (ORDER_UPDATED) để mọi quầy luôn giống nhau
        send(NetworkPacket.buildOrderStatusUpdate(orderId, tableId == null ? "" : tableId, newStatus));
    }

    // ------------------------------------------------------------------ đặt bàn

    private void setResDate(java.util.Date d) {
        resDate = d;
        txtResDate.setText(DAY.format(d));
        requestReservations();
    }

    private void shiftResDate(int days) {
        setResDate(new java.util.Date(resDate.getTime() + days * 86_400_000L));
    }

    /** Dòng đặt bàn đang chọn: các trường RESERVATION đã giải mã, null nếu chưa chọn */
    private String[] selectedReservation() {
        int row = tblReservations.getSelectedRow();
        if (row < 0) {
            toast("Chọn một lượt đặt trong bảng trước.");
            return null;
        }
        return reservations.get(tblReservations.convertRowIndexToModel(row));
    }

    private void editSelectedReservation() {
        String[] r = selectedReservation();
        if (r == null) return;
        if (!Reservation.STATUS_BOOKED.equals(r[12])) {
            toast("Chỉ sửa được lượt đặt đang ở trạng thái ĐÃ ĐẶT.");
            return;
        }
        openReservation(r, null);
    }

    private void changeReservationStatus(String status) {
        String[] r = selectedReservation();
        if (r == null) return;
        if (!Reservation.STATUS_BOOKED.equals(r[12])) {
            toast("Lượt đặt này đã " + r[12].toLowerCase() + ".");
            return;
        }
        long deposit = NetworkPacket.parseInt(r[10], 0);
        int ok = JOptionPane.showConfirmDialog(this, "Chuyển lượt đặt của " + r[2] + " (" + r[5].substring(11) + ") sang \"" + status + "\"?"
                + (deposit > 0 ? "\nKhách đã đặt cọc " + Theme.money(deposit) + ": nhớ hoàn hoặc giữ cọc theo chính sách nhà hàng." : ""),
                "Đặt bàn", JOptionPane.YES_NO_OPTION);
        if (ok == JOptionPane.YES_OPTION) send(NetworkPacket.buildReservationStatus(Integer.parseInt(r[1]), status));
    }

    private void checkInSelected() {
        String[] r = selectedReservation();
        if (r == null) return;
        if (!Reservation.STATUS_BOOKED.equals(r[12])) {
            toast("Lượt đặt này đã " + r[12].toLowerCase() + ".");
            return;
        }
        int guests = NetworkPacket.parseInt(r[4], 1);
        String table = r[7];
        if (table.isEmpty() || !"0".equals(tableInfos.getOrDefault(table, new String[]{"", "", "", "0", "", "", "0"})[6])) {
            // Chưa chọn bàn hoặc bàn đã đặt đang có khách khác: chọn bàn trống đủ ghế
            List<String> ids = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (String[] i : tableInfos.values()) {
                if ("0".equals(i[6]) && NetworkPacket.parseInt(i[3], 0) >= guests && !DiningTable.STATUS_CLEANING.equals(i[5])) {
                    ids.add(i[1]);
                    names.add(i[2] + " · " + i[3] + " ghế · " + i[4]);
                }
            }
            if (ids.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Không còn bàn trống đủ " + guests + " ghế.", "Nhận bàn", JOptionPane.WARNING_MESSAGE);
                return;
            }
            Object pick = JOptionPane.showInputDialog(this, (table.isEmpty() ? "Lượt đặt chưa chọn bàn." : r[8] + " đang có khách.")
                    + "\nChọn bàn cho " + r[2] + " (" + guests + " khách):", "Nhận bàn", JOptionPane.QUESTION_MESSAGE, null, names.toArray(), names.get(0));
            if (pick == null) return;
            table = ids.get(names.indexOf(pick));
        }
        int waiterId = pickWaiter("Nhân viên phục vụ cho " + r[2] + ":");
        if (waiterId < 0) return;
        send(NetworkPacket.buildCheckIn(Integer.parseInt(r[1]), table, waiterId));
    }

    /** Chọn nhân viên phục vụ (0 = chưa gán, -1 = bấm Hủy) */
    public int pickWaiter(String message) {
        List<String> names = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        names.add("(Chưa gán)");
        ids.add(0);
        for (Map.Entry<Integer, String> w : waiters.entrySet()) {
            names.add(waiterLabel(w.getKey()));
            ids.add(w.getKey());
        }
        Object pick = JOptionPane.showInputDialog(this, message, "Nhân viên phục vụ", JOptionPane.QUESTION_MESSAGE, null, names.toArray(), names.get(0));
        return pick == null ? -1 : ids.get(names.indexOf(pick));
    }

    private void fillReservationTable() {
        resModel.setRowCount(0);
        int booked = 0;
        for (String[] r : reservations) {
            if (Reservation.STATUS_BOOKED.equals(r[12])) booked++;
            resModel.addRow(new Object[]{r[1], r[5].length() >= 16 ? r[5].substring(11, 16) : r[5], r[2], r[3], r[4],
                    r[8].isEmpty() ? "(chưa chọn)" : r[8], NetworkPacket.parseInt(r[10], 0) > 0 ? Theme.money(NetworkPacket.parseInt(r[10], 0)) : "",
                    r[11], r[12]});
        }
        if (ISO_DAY.format(resDate).equals(ISO_DAY.format(new java.util.Date()))) {
            lblStatReservations.setText("Đặt trước hôm nay: " + booked);
        }
        tabRight.setTitleAt(1, "Đặt bàn" + (booked > 0 ? " (" + booked + ")" : ""));
    }

    // ------------------------------------------------------------------ tin nhắn

    /** Bảng cuộc trò chuyện: bàn có tin mới nhất ở trên, bàn chưa có tin ở dưới; số tin chưa đọc hiện cạnh tên bàn */
    private void refreshConversationList() {
        List<String> ids = new ArrayList<>(tableInfos.keySet());
        List<String> order = new ArrayList<>(tableInfos.keySet());
        ids.sort((x, y) -> {
            String[] cx = conversations.get(x), cy = conversations.get(y);
            if (cx == null && cy == null) return Integer.compare(order.indexOf(x), order.indexOf(y));
            if (cx == null) return 1;
            if (cy == null) return -1;
            return Long.compare(Long.parseLong(cy[2]), Long.parseLong(cx[2]));
        });
        fillingConv = true;
        convModel.setRowCount(0);
        convRows.clear();
        int selected = -1;
        for (String id : ids) {
            String[] c = conversations.get(id);
            int n = chatUnread.getOrDefault(id, 0);
            String when = c == null ? "" : (c[0].startsWith(new SimpleDateFormat("dd/MM").format(new java.util.Date())) && c[0].length() > 6 ? c[0].substring(6) : c[0].substring(0, Math.min(5, c[0].length())));
            convModel.addRow(new Object[]{tableName(id) + (n > 0 ? "  ● " + n : ""), c == null ? "(chưa có tin nhắn)" : c[1], when});
            convRows.add(id);
            if (id.equals(chatTable)) selected = convRows.size() - 1;
        }
        if (selected >= 0) tblConversations.setRowSelectionInterval(selected, selected);
        fillingConv = false;
        updateTabTitles();
    }

    /** Ghi nhận hoạt động mới của một bàn (tin nhắn, chuông) để đưa cuộc trò chuyện lên đầu danh sách */
    private void recordActivity(String tableId, String preview, boolean incoming) {
        conversations.put(tableId, new String[]{new SimpleDateFormat("dd/MM HH:mm").format(new java.util.Date()), preview,
                String.valueOf(System.currentTimeMillis())});
        boolean viewing = tableId.equals(chatTable) && tabRight.getSelectedComponent() == splitMessages;
        if (incoming && !viewing) chatUnread.merge(tableId, 1, Integer::sum);
        refreshConversationList();
    }

    /** Mở cuộc trò chuyện riêng với một bàn và tải lịch sử theo phạm vi đang chọn */
    public void openConversation(String tableId) {
        chatTable = tableId;
        chatUnread.remove(tableId);
        String[] info = tableInfos.get(tableId);
        String title = tableName(tableId);
        if (info != null && !"0".equals(info[6])) {
            title += (info[7].isEmpty() ? "" : " · Khách: " + info[7]) + (info[9].isEmpty() ? " · chưa gán phục vụ" : " · PV: " + info[9]);
        }
        lblConvTitle.setText(title);
        setReplyEnabled(true);
        chat.clear();
        if (client != null && client.isConnected()) client.send(NetworkPacket.buildGetChat(tableId, chatScope()));
        refreshConversationList();
        txtReply.requestFocusInWindow();
    }

    private String chatScope() {
        switch (cboChatScope.getSelectedIndex()) {
            case 1: return "TODAY";
            case 2: return "WEEK";
            default: return "SESSION";
        }
    }

    private void setReplyEnabled(boolean enabled) {
        txtReply.setEnabled(enabled);
        btnSendReply.setEnabled(enabled);
    }

    private void sendReply() {
        String msg = txtReply.getText().trim();
        if (msg.isEmpty() || chatTable == null) return;
        send(NetworkPacket.buildChatToTable(chatTable, msg));
        chat.addMessage("Bạn (Lễ tân)", msg, new SimpleDateFormat("HH:mm").format(new java.util.Date()), true);
        recordActivity(chatTable, "Lễ tân: " + msg, false);
        txtReply.setText("");
    }

    private void updateTabTitles() {
        int total = chatUnread.values().stream().mapToInt(Integer::intValue).sum();
        tabRight.setTitleAt(2, total > 0 ? "Tin nhắn  ● " + total : "Tin nhắn");
    }

    /** "Lê Minh Phục (2 bàn · đang trực)" để Lễ tân chia bàn đều cho các phục vụ */
    public String waiterLabel(int waiterId) {
        String name = waiters.get(waiterId);
        if (name == null) return "(Chưa gán)";
        int[] l = waiterLoad.getOrDefault(waiterId, new int[]{0, 0});
        return name + " (" + l[1] + " bàn · " + (l[0] == 1 ? "đang trực" : "chưa vào ca") + ")";
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
            lblStaff.setText("Nhân viên: " + client.getStaffName() + " (" + client.getStaffRole() + ")");
            setTitle("Quầy Lễ tân - " + client.getStaffName());
            ordersModel.setRowCount(0);
            chat.clear();
        });
        client.send(NetworkPacket.buildGetTables());
        client.send(NetworkPacket.buildGetConversations());
        client.send(NetworkPacket.buildGetReservations(ISO_DAY.format(resDate)));
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
            toast("Mất kết nối máy chủ: " + reason);
        });
    }

    @Override
    public void onAuthFailed(String reason) {
        SwingUtilities.invokeLater(() -> {
            if (pendingLogin != null) {
                pendingLogin.showError(reason);
            } else {
                JOptionPane.showMessageDialog(this, reason, "Không thể kết nối", JOptionPane.WARNING_MESSAGE);
            }
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
            handleMessage(f, raw);
            for (MessageListener l : listeners) l.onServerMessage(f, raw);
        });
    }

    private void handleMessage(String[] f, String raw) {
        String time = new SimpleDateFormat("HH:mm").format(new java.util.Date());
        switch (f[0]) {
            case "TABLES_BEGIN":
                loadingTables = true;
                tableInfos.clear();
                waiters.clear();
                break;
            case "TABLE_INFO": {
                if (f.length < 12) return;
                String[] info = new String[14];
                for (int i = 0; i < info.length; i++) info[i] = i < f.length ? NetworkPacket.dec(f[i]) : "";
                boolean isNew = !tableInfos.containsKey(info[1]);
                tableInfos.put(info[1], info);
                if (!loadingTables) {
                    if (isNew) {
                        refreshAreaAndTableLists();
                        rebuildMap();
                    } else {
                        updateCard(info[1]);
                    }
                    refreshStats();
                }
                break;
            }
            case "WAITER":
                // WAITER|MÃ|TÊN|ĐANG_TRỰC|SỐ_BÀN
                if (f.length >= 3) {
                    int id = NetworkPacket.parseInt(f[1], 0);
                    waiters.put(id, NetworkPacket.dec(f[2]));
                    waiterLoad.put(id, new int[]{f.length > 3 ? NetworkPacket.parseInt(f[3], 0) : 0, f.length > 4 ? NetworkPacket.parseInt(f[4], 0) : 0});
                }
                break;
            case "WAITERS_CHANGED":
                requestTables();
                break;
            case "TABLES_END":
                loadingTables = false;
                cards.keySet().retainAll(tableInfos.keySet());
                for (String id : tableInfos.keySet()) updateCard(id);
                refreshAreaAndTableLists();
                rebuildMap();
                refreshStats();
                break;
            case "TABLES_CHANGED":
                requestTables();
                break;
            case "TABLE_STATE": {
                String[] info = tableInfos.get(f[1]);
                if (info != null) {
                    info[10] = "ONLINE".equals(f[2]) ? "1" : "0";
                    updateCard(f[1]);
                }
                break;
            }
            case "OLD_ORDER":
                if (f.length >= 9) addOrderRow(f);
                break;
            case "NEW_ORDER": {
                if (f.length < 9) return;
                addOrderRow(f);
                String no = Order.formatOrderNo(NetworkPacket.parseInt(f[2], 0));
                setAlert(f[3], "Đơn mới " + no);
                Toolkit.getDefaultToolkit().beep();
                toast(tableName(f[3]) + " gửi đơn " + no + " (" + Theme.money(NetworkPacket.parseInt(f[6], 0)) + ")"
                        + (f[7].isEmpty() ? "" : " · ghi chú: " + NetworkPacket.dec(f[7])));
                break;
            }
            case "ORDER_UPDATED": {
                // ORDER_UPDATED|MÃ_ĐƠN|TRẠNG_THÁI|STT|BÀN
                for (int r = 0; r < ordersModel.getRowCount(); r++) {
                    if (f[1].equals(String.valueOf(ordersModel.getValueAt(r, 0)))) ordersModel.setValueAt(f[2], r, 6);
                }
                if (Order.STATUS_DONE.equals(f[2]) && f.length > 4) {
                    Toolkit.getDefaultToolkit().beep();
                    setAlert(f[4], "Bếp xong " + Order.formatOrderNo(NetworkPacket.parseInt(f[3], 0)) + " - mang ra");
                    toast("Bếp đã nấu xong đơn " + Order.formatOrderNo(NetworkPacket.parseInt(f[3], 0)) + " của " + tableName(f[4]) + " → mang ra cho khách.");
                } else if (Order.STATUS_SERVED.equals(f[2]) && f.length > 4) {
                    String a = alerts.get(f[4]);
                    if (a != null && (a.startsWith("Bếp xong") || a.startsWith("Đơn mới"))) clearAlert(f[4]);
                }
                break;
            }
            case "RING": {
                // RING|BÀN|LÝ_DO|PHỤC_VỤ
                String reason = f.length > 2 ? f[2] : "Gọi nhân viên";
                String waiter = f.length > 3 ? NetworkPacket.dec(f[3]) : "";
                setAlert(f[1], "Gọi: " + reason);
                Toolkit.getDefaultToolkit().beep();
                if (f[1].equals(chatTable)) chat.addNotice(time + " · khách bấm chuông: " + reason + (waiter.isEmpty() ? "" : " (PV: " + waiter + ")"));
                recordActivity(f[1], "Bấm chuông: " + reason, true);
                toast(tableName(f[1]) + " gọi: " + reason + (waiter.isEmpty() ? "" : " → báo " + waiter));
                break;
            }
            case "PAY_REQUEST": {
                // PAY_REQUEST|BÀN|HÌNH_THỨC|TẠM_TÍNH
                String method = NetworkPacket.dec(f[2]);
                setAlert(f[1], "Yêu cầu thanh toán · " + method);
                Toolkit.getDefaultToolkit().beep();
                toast(tableName(f[1]) + " yêu cầu thanh toán " + Theme.money(NetworkPacket.parseInt(f[3], 0)) + " bằng " + method);
                break;
            }
            case "CHAT_EVENT": {
                // CHAT_EVENT|BÀN|IN(khách)/OUT(nhân viên)|TÊN_NGƯỜI_GỬI|GIỜ|NỘI_DUNG
                if (f.length < 6) return;
                boolean in = "IN".equals(f[2]);
                String name = NetworkPacket.dec(f[3]);
                String text = NetworkPacket.dec(f[5]);
                if (f[1].equals(chatTable)) chat.addMessage(name, text, f[4], !in);
                recordActivity(f[1], in ? text : name + ": " + text, in);
                if (in) {
                    Toolkit.getDefaultToolkit().beep();
                    toast(name + " nhắn: " + text);
                }
                break;
            }
            case "CONV":
                // CONV|BÀN|GIỜ_TIN_CUỐI|TIN_CUỐI|SỐ_TIN_HÔM_NAY (đã sắp mới -> cũ)
                if (f.length >= 4) conversations.put(f[1], new String[]{f[2], NetworkPacket.dec(f[3]), String.valueOf(1_000_000_000L - convLoadIndex++)});
                break;
            case "CONV_END":
                convLoadIndex = 0;
                refreshConversationList();
                break;
            case "CHAT_HISTORY_BEGIN":
                if (f[1].equals(chatTable)) chat.clear();
                break;
            case "CHAT_ITEM":
                // CHAT_ITEM|BÀN|IN/OUT|TÊN|dd/MM HH:mm|LOẠI|NỘI_DUNG
                if (f.length >= 7 && f[1].equals(chatTable)) {
                    if ("RING".equals(f[5])) chat.addNotice(f[4] + " · khách bấm chuông: " + NetworkPacket.dec(f[6]));
                    else chat.addMessage(NetworkPacket.dec(f[3]), NetworkPacket.dec(f[6]), f[4], "OUT".equals(f[2]));
                }
                break;
            case "SERVER_ACK":
                toast(f.length > 1 ? f[1] : "");
                break;
            case "TABLE_MOVED":
                toast("Đã chuyển khách từ " + tableName(f[1]) + " sang " + tableName(f[2]) + ".");
                openTableDetail(f[2]);
                break;
            case "SESSION_CANCELLED": {
                long deposit = f.length > 2 ? NetworkPacket.parseInt(f[2], 0) : 0;
                clearAlert(f[1]);
                toast("Đã hủy " + tableName(f[1]) + ", bàn trở về TRỐNG." + (deposit > 0 ? " Giữ tiền cọc " + Theme.money(deposit) + "." : ""));
                break;
            }
            case "CANCEL_FAIL":
                JOptionPane.showMessageDialog(this, NetworkPacket.dec(f[2]), "Không hủy được bàn", JOptionPane.WARNING_MESSAGE);
                break;
            case "MOVE_FAIL":
                JOptionPane.showMessageDialog(this, NetworkPacket.dec(f[2]), "Không chuyển được bàn", JOptionPane.WARNING_MESSAGE);
                break;
            case "RESERVATIONS_BEGIN":
                reservations.clear();
                break;
            case "RESERVATION": {
                String[] r = new String[13];
                for (int i = 0; i < r.length; i++) r[i] = i < f.length ? NetworkPacket.dec(f[i]) : "";
                reservations.add(r);
                break;
            }
            case "RESERVATIONS_END":
                fillReservationTable();
                break;
            case "RESERVATIONS_CHANGED":
                if (f.length > 1 && f[1].equals(ISO_DAY.format(resDate))) requestReservations();
                break;
            case "RESERVATION_SAVED":
                requestReservations();
                break;
            case "CHECKED_IN":
                toast("Khách đặt trước đã nhận " + tableName(f[1]) + ".");
                requestReservations();
                openTableDetail(f[1]);
                break;
            case "RESERVATION_FAIL":
                if (!ReservationDialog.isOpen()) {
                    JOptionPane.showMessageDialog(this, NetworkPacket.dec(f[1]), "Đặt bàn", JOptionPane.WARNING_MESSAGE);
                }
                break;
            case "BILL":
                if (!CheckoutDialog.isOpen()) showBill(this, f);
                break;
            default:
                break;
        }
    }

    /**
     * Hiện hóa đơn sau khi thanh toán:
     * BILL|BÀN|TÊN_BÀN|MÃ_PHIÊN|TẠM_TÍNH|GIẢM_GIÁ|TỔNG|HÌNH_THỨC|TÊN_KHÁCH|SỐ_KHÁCH|PHỤC_VỤ|CÁC_DÒNG|TIỀN_CỌC|CÒN_PHẢI_TRẢ
     */
    public static void showBill(Component parent, String[] f) {
        if (f.length < 14) return;
        StringBuilder sb = new StringBuilder();
        sb.append("              HÓA ĐƠN THANH TOÁN\n");
        sb.append("  ").append(NetworkPacket.dec(f[2])).append("   ·   Mã lượt: HD").append(f[3]).append("\n");
        sb.append("  ").append(new SimpleDateFormat("HH:mm dd/MM/yyyy").format(new java.util.Date())).append("\n");
        if (!NetworkPacket.dec(f[8]).isEmpty()) sb.append("  Khách: ").append(NetworkPacket.dec(f[8])).append("   (").append(f[9]).append(" người)\n");
        if (!NetworkPacket.dec(f[10]).isEmpty()) sb.append("  Phục vụ: ").append(NetworkPacket.dec(f[10])).append("\n");
        sb.append("  ------------------------------------------------\n");
        int no = 0;
        for (String line : NetworkPacket.dec(f[11]).split("\n")) {
            String[] c = line.split("\t");
            if (c.length >= 4) {
                String name = c[0].length() > 22 ? c[0].substring(0, 21) + "…" : c[0];
                sb.append(String.format("  %2d. %-23s x%-3s %14s%n", ++no, name, c[1], Theme.money(Long.parseLong(c[3]))));
            }
        }
        sb.append("  ------------------------------------------------\n");
        sb.append(String.format("  %-32s %14s%n", "Tạm tính", Theme.money(Long.parseLong(f[4]))));
        sb.append(String.format("  %-32s %14s%n", "Giảm giá", "-" + Theme.money(Long.parseLong(f[5]))));
        if (Long.parseLong(f[12]) > 0) sb.append(String.format("  %-32s %14s%n", "Đã đặt cọc", "-" + Theme.money(Long.parseLong(f[12]))));
        sb.append(String.format("  %-32s %14s%n", "KHÁCH TRẢ (" + NetworkPacket.dec(f[7]) + ")", Theme.money(Long.parseLong(f[13]))));
        sb.append("\n          Cảm ơn quý khách, hẹn gặp lại!\n");
        JTextArea area = new JTextArea(sb.toString());
        area.setEditable(false);
        area.setFont(new Font("Consolas", Font.PLAIN, 13));
        JOptionPane.showMessageDialog(parent, new JScrollPane(area), "Hóa đơn - " + NetworkPacket.dec(f[2]), JOptionPane.PLAIN_MESSAGE);
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
        pnlStats = new javax.swing.JPanel();
        lblStatFreeTables = new javax.swing.JLabel();
        lblStatFreeSeats = new javax.swing.JLabel();
        lblStatGuests = new javax.swing.JLabel();
        lblStatCleaning = new javax.swing.JLabel();
        lblStatReservations = new javax.swing.JLabel();
        pnlHeaderRight = new javax.swing.JPanel();
        lblConnection = new javax.swing.JLabel();
        lblClock = new javax.swing.JLabel();
        splitMain = new javax.swing.JSplitPane();
        pnlMap = new javax.swing.JPanel();
        pnlMapTools = new javax.swing.JPanel();
        lblArea = new javax.swing.JLabel();
        cboArea = new javax.swing.JComboBox<>();
        btnWalkIn = new javax.swing.JButton();
        btnNewReservationTop = new javax.swing.JButton();
        btnRefreshTables = new javax.swing.JButton();
        scrollMap = new javax.swing.JScrollPane();
        pnlTableMap = new javax.swing.JPanel();
        lblLegend = new javax.swing.JLabel();
        pnlRight = new javax.swing.JPanel();
        tabRight = new javax.swing.JTabbedPane();
        pnlOrders = new javax.swing.JPanel();
        pnlOrderFilter = new javax.swing.JPanel();
        lblTableFilter = new javax.swing.JLabel();
        cboTableFilter = new javax.swing.JComboBox<>();
        lblStatusFilter = new javax.swing.JLabel();
        cboStatusFilter = new javax.swing.JComboBox<>();
        scrollOrders = new javax.swing.JScrollPane();
        tblOrders = new javax.swing.JTable();
        pnlOrderActions = new javax.swing.JPanel();
        btnOpenOrderTable = new javax.swing.JButton();
        btnCancelOrder = new javax.swing.JButton();
        btnServed = new javax.swing.JButton();
        pnlReservations = new javax.swing.JPanel();
        pnlResTools = new javax.swing.JPanel();
        lblResDate = new javax.swing.JLabel();
        btnResPrev = new javax.swing.JButton();
        txtResDate = new javax.swing.JTextField();
        btnResNext = new javax.swing.JButton();
        btnResToday = new javax.swing.JButton();
        btnNewReservation = new javax.swing.JButton();
        scrollReservations = new javax.swing.JScrollPane();
        tblReservations = new javax.swing.JTable();
        pnlResActions = new javax.swing.JPanel();
        btnEditReservation = new javax.swing.JButton();
        btnCancelReservation = new javax.swing.JButton();
        btnNoShow = new javax.swing.JButton();
        btnCheckIn = new javax.swing.JButton();
        splitMessages = new javax.swing.JSplitPane();
        pnlConvList = new javax.swing.JPanel();
        lblConvHint = new javax.swing.JLabel();
        scrollConversations = new javax.swing.JScrollPane();
        tblConversations = new javax.swing.JTable();
        pnlMessages = new javax.swing.JPanel();
        pnlConvHeader = new javax.swing.JPanel();
        lblConvTitle = new javax.swing.JLabel();
        cboChatScope = new javax.swing.JComboBox<>();
        pnlChatHost = new javax.swing.JPanel();
        pnlReply = new javax.swing.JPanel();
        txtReply = new javax.swing.JTextField();
        btnSendReply = new javax.swing.JButton();
        pnlStatusBar = new javax.swing.JPanel();
        lblToast = new javax.swing.JLabel();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("Quầy Lễ tân");
        getContentPane().setLayout(new java.awt.BorderLayout());

        pnlHeader.setBackground(new java.awt.Color(31, 36, 48));
        pnlHeader.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 16, 10, 16));
        pnlHeader.setLayout(new java.awt.BorderLayout(16, 0));

        pnlHeaderLeft.setOpaque(false);
        pnlHeaderLeft.setLayout(new java.awt.GridLayout(2, 1, 0, 0));

        lblTitle.setFont(new java.awt.Font("Segoe UI", 1, 18)); // NOI18N
        lblTitle.setForeground(new java.awt.Color(255, 255, 255));
        lblTitle.setText("QUẦY LỄ TÂN");
        pnlHeaderLeft.add(lblTitle);

        lblStaff.setFont(new java.awt.Font("Segoe UI", 0, 12)); // NOI18N
        lblStaff.setForeground(new java.awt.Color(200, 206, 216));
        lblStaff.setText("Nhân viên: -");
        pnlHeaderLeft.add(lblStaff);

        pnlHeader.add(pnlHeaderLeft, java.awt.BorderLayout.WEST);

        pnlStats.setOpaque(false);
        pnlStats.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 6));

        lblStatFreeTables.setBackground(new java.awt.Color(44, 52, 68));
        lblStatFreeTables.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        lblStatFreeTables.setForeground(new java.awt.Color(255, 255, 255));
        lblStatFreeTables.setText("Bàn trống: -");
        lblStatFreeTables.setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 10, 5, 10));
        lblStatFreeTables.setOpaque(true);
        pnlStats.add(lblStatFreeTables);

        lblStatFreeSeats.setBackground(new java.awt.Color(44, 52, 68));
        lblStatFreeSeats.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        lblStatFreeSeats.setForeground(new java.awt.Color(255, 255, 255));
        lblStatFreeSeats.setText("Ghế trống: -");
        lblStatFreeSeats.setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 10, 5, 10));
        lblStatFreeSeats.setOpaque(true);
        pnlStats.add(lblStatFreeSeats);

        lblStatGuests.setBackground(new java.awt.Color(44, 52, 68));
        lblStatGuests.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        lblStatGuests.setForeground(new java.awt.Color(255, 255, 255));
        lblStatGuests.setText("Khách đang ngồi: -");
        lblStatGuests.setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 10, 5, 10));
        lblStatGuests.setOpaque(true);
        pnlStats.add(lblStatGuests);

        lblStatCleaning.setBackground(new java.awt.Color(44, 52, 68));
        lblStatCleaning.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        lblStatCleaning.setForeground(new java.awt.Color(255, 255, 255));
        lblStatCleaning.setText("Cần dọn: -");
        lblStatCleaning.setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 10, 5, 10));
        lblStatCleaning.setOpaque(true);
        pnlStats.add(lblStatCleaning);

        lblStatReservations.setBackground(new java.awt.Color(44, 52, 68));
        lblStatReservations.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        lblStatReservations.setForeground(new java.awt.Color(255, 255, 255));
        lblStatReservations.setText("Đặt trước hôm nay: -");
        lblStatReservations.setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 10, 5, 10));
        lblStatReservations.setOpaque(true);
        pnlStats.add(lblStatReservations);

        pnlHeader.add(pnlStats, java.awt.BorderLayout.CENTER);

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

        splitMain.setDividerLocation(640);
        splitMain.setResizeWeight(0.5);
        pnlMap.setBackground(new java.awt.Color(244, 245, 247));
        pnlMap.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 4));
        pnlMap.setLayout(new java.awt.BorderLayout(0, 8));

        pnlMapTools.setOpaque(false);
        pnlMapTools.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));

        lblArea.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblArea.setForeground(new java.awt.Color(44, 62, 80));
        lblArea.setText("Khu vực:");
        pnlMapTools.add(lblArea);

        cboArea.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        cboArea.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "Tất cả khu vực" }));
        cboArea.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cboAreaActionPerformed(evt);
            }
        });
        pnlMapTools.add(cboArea);

        btnWalkIn.setBackground(new java.awt.Color(39, 174, 96));
        btnWalkIn.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnWalkIn.setForeground(new java.awt.Color(255, 255, 255));
        btnWalkIn.setText("+ Xếp bàn cho khách");
        btnWalkIn.setFocusable(false);
        btnWalkIn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnWalkInActionPerformed(evt);
            }
        });
        pnlMapTools.add(btnWalkIn);

        btnNewReservationTop.setBackground(new java.awt.Color(142, 68, 173));
        btnNewReservationTop.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnNewReservationTop.setForeground(new java.awt.Color(255, 255, 255));
        btnNewReservationTop.setText("+ Đặt bàn trước");
        btnNewReservationTop.setFocusable(false);
        btnNewReservationTop.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnNewReservationTopActionPerformed(evt);
            }
        });
        pnlMapTools.add(btnNewReservationTop);

        btnRefreshTables.setBackground(new java.awt.Color(236, 240, 241));
        btnRefreshTables.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnRefreshTables.setForeground(new java.awt.Color(44, 62, 80));
        btnRefreshTables.setText("Làm mới");
        btnRefreshTables.setFocusable(false);
        btnRefreshTables.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnRefreshTablesActionPerformed(evt);
            }
        });
        pnlMapTools.add(btnRefreshTables);

        pnlMap.add(pnlMapTools, java.awt.BorderLayout.NORTH);

        pnlTableMap.setBackground(new java.awt.Color(244, 245, 247));
        pnlTableMap.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 4, 4, 4));
        pnlTableMap.setLayout(new java.awt.GridLayout(0, 3, 10, 10));

        scrollMap.setViewportView(pnlTableMap);

        pnlMap.add(scrollMap, java.awt.BorderLayout.CENTER);

        lblLegend.setFont(new java.awt.Font("Segoe UI", 0, 11)); // NOI18N
        lblLegend.setForeground(new java.awt.Color(127, 140, 141));
        lblLegend.setText("<html><span style='color:#27ae60'>■</span> Trống &nbsp; <span style='color:#2980b9'>■</span> Đang phục vụ &nbsp; <span style='color:#8e44ad'>■</span> Đã đặt (trong 60 phút tới) &nbsp; <span style='color:#95a5a6'>■</span> Cần dọn &nbsp; <span style='color:#e74c3c'>■</span> Viền đỏ nhấp nháy: khách đang gọi &nbsp;·&nbsp; Bấm vào bàn để xem chi tiết</html>");
        pnlMap.add(lblLegend, java.awt.BorderLayout.SOUTH);

        splitMain.setLeftComponent(pnlMap);

        pnlRight.setBackground(new java.awt.Color(244, 245, 247));
        pnlRight.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 4, 10, 10));
        pnlRight.setLayout(new java.awt.BorderLayout());

        tabRight.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        pnlOrders.setBackground(new java.awt.Color(255, 255, 255));
        pnlOrders.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 8, 8, 8));
        pnlOrders.setLayout(new java.awt.BorderLayout(0, 8));

        pnlOrderFilter.setOpaque(false);
        pnlOrderFilter.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));

        lblTableFilter.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblTableFilter.setForeground(new java.awt.Color(44, 62, 80));
        lblTableFilter.setText("Bàn:");
        pnlOrderFilter.add(lblTableFilter);

        cboTableFilter.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        cboTableFilter.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "Tất cả các bàn" }));
        cboTableFilter.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cboTableFilterActionPerformed(evt);
            }
        });
        pnlOrderFilter.add(cboTableFilter);

        lblStatusFilter.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblStatusFilter.setForeground(new java.awt.Color(44, 62, 80));
        lblStatusFilter.setText("Trạng thái:");
        pnlOrderFilter.add(lblStatusFilter);

        cboStatusFilter.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        cboStatusFilter.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "Chưa phục vụ", "Tất cả", "CHỜ BẾP", "ĐANG NẤU", "ĐÃ XONG", "ĐÃ PHỤC VỤ", "ĐÃ HỦY" }));
        cboStatusFilter.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cboStatusFilterActionPerformed(evt);
            }
        });
        pnlOrderFilter.add(cboStatusFilter);

        pnlOrders.add(pnlOrderFilter, java.awt.BorderLayout.NORTH);

        tblOrders.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        tblOrders.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {

            },
            new String [] {
                "Mã", "STT", "Bàn", "Giờ", "Món", "Tổng tiền", "Trạng thái", "Ghi chú"
            }
        ) {
            boolean[] canEdit = new boolean [] {
                false, false, false, false, false, false, false, false
            };

            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return canEdit [columnIndex];
            }
        });
        tblOrders.setRowHeight(28);
        scrollOrders.setViewportView(tblOrders);

        pnlOrders.add(scrollOrders, java.awt.BorderLayout.CENTER);

        pnlOrderActions.setOpaque(false);
        pnlOrderActions.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));

        btnOpenOrderTable.setBackground(new java.awt.Color(236, 240, 241));
        btnOpenOrderTable.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnOpenOrderTable.setForeground(new java.awt.Color(44, 62, 80));
        btnOpenOrderTable.setText("Xem bàn");
        btnOpenOrderTable.setFocusable(false);
        btnOpenOrderTable.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnOpenOrderTableActionPerformed(evt);
            }
        });
        pnlOrderActions.add(btnOpenOrderTable);

        btnCancelOrder.setBackground(new java.awt.Color(231, 76, 60));
        btnCancelOrder.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnCancelOrder.setForeground(new java.awt.Color(255, 255, 255));
        btnCancelOrder.setText("Hủy đơn");
        btnCancelOrder.setFocusable(false);
        btnCancelOrder.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnCancelOrderActionPerformed(evt);
            }
        });
        pnlOrderActions.add(btnCancelOrder);

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
        pnlOrderActions.add(btnServed);

        pnlOrders.add(pnlOrderActions, java.awt.BorderLayout.SOUTH);

        tabRight.addTab("Đơn hàng", pnlOrders);

        pnlReservations.setBackground(new java.awt.Color(255, 255, 255));
        pnlReservations.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 8, 8, 8));
        pnlReservations.setLayout(new java.awt.BorderLayout(0, 8));

        pnlResTools.setOpaque(false);
        pnlResTools.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));

        lblResDate.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblResDate.setForeground(new java.awt.Color(44, 62, 80));
        lblResDate.setText("Ngày:");
        pnlResTools.add(lblResDate);

        btnResPrev.setBackground(new java.awt.Color(236, 240, 241));
        btnResPrev.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnResPrev.setForeground(new java.awt.Color(44, 62, 80));
        btnResPrev.setText("‹");
        btnResPrev.setFocusable(false);
        btnResPrev.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnResPrevActionPerformed(evt);
            }
        });
        pnlResTools.add(btnResPrev);

        txtResDate.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        txtResDate.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        txtResDate.setColumns(8);
        txtResDate.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                txtResDateActionPerformed(evt);
            }
        });
        pnlResTools.add(txtResDate);

        btnResNext.setBackground(new java.awt.Color(236, 240, 241));
        btnResNext.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnResNext.setForeground(new java.awt.Color(44, 62, 80));
        btnResNext.setText("›");
        btnResNext.setFocusable(false);
        btnResNext.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnResNextActionPerformed(evt);
            }
        });
        pnlResTools.add(btnResNext);

        btnResToday.setBackground(new java.awt.Color(236, 240, 241));
        btnResToday.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnResToday.setForeground(new java.awt.Color(44, 62, 80));
        btnResToday.setText("Hôm nay");
        btnResToday.setFocusable(false);
        btnResToday.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnResTodayActionPerformed(evt);
            }
        });
        pnlResTools.add(btnResToday);

        btnNewReservation.setBackground(new java.awt.Color(142, 68, 173));
        btnNewReservation.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnNewReservation.setForeground(new java.awt.Color(255, 255, 255));
        btnNewReservation.setText("+ Đặt bàn mới");
        btnNewReservation.setFocusable(false);
        btnNewReservation.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnNewReservationActionPerformed(evt);
            }
        });
        pnlResTools.add(btnNewReservation);

        pnlReservations.add(pnlResTools, java.awt.BorderLayout.NORTH);

        tblReservations.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        tblReservations.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {

            },
            new String [] {
                "Mã", "Giờ", "Khách", "SĐT", "Số khách", "Bàn", "Tiền cọc", "Ghi chú", "Trạng thái"
            }
        ) {
            boolean[] canEdit = new boolean [] {
                false, false, false, false, false, false, false, false, false
            };

            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return canEdit [columnIndex];
            }
        });
        tblReservations.setRowHeight(28);
        scrollReservations.setViewportView(tblReservations);

        pnlReservations.add(scrollReservations, java.awt.BorderLayout.CENTER);

        pnlResActions.setOpaque(false);
        pnlResActions.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));

        btnEditReservation.setBackground(new java.awt.Color(236, 240, 241));
        btnEditReservation.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnEditReservation.setForeground(new java.awt.Color(44, 62, 80));
        btnEditReservation.setText("Sửa");
        btnEditReservation.setFocusable(false);
        btnEditReservation.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnEditReservationActionPerformed(evt);
            }
        });
        pnlResActions.add(btnEditReservation);

        btnCancelReservation.setBackground(new java.awt.Color(236, 240, 241));
        btnCancelReservation.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnCancelReservation.setForeground(new java.awt.Color(44, 62, 80));
        btnCancelReservation.setText("Khách hủy");
        btnCancelReservation.setFocusable(false);
        btnCancelReservation.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnCancelReservationActionPerformed(evt);
            }
        });
        pnlResActions.add(btnCancelReservation);

        btnNoShow.setBackground(new java.awt.Color(236, 240, 241));
        btnNoShow.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnNoShow.setForeground(new java.awt.Color(44, 62, 80));
        btnNoShow.setText("Không đến");
        btnNoShow.setFocusable(false);
        btnNoShow.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnNoShowActionPerformed(evt);
            }
        });
        pnlResActions.add(btnNoShow);

        btnCheckIn.setBackground(new java.awt.Color(39, 174, 96));
        btnCheckIn.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnCheckIn.setForeground(new java.awt.Color(255, 255, 255));
        btnCheckIn.setText("Khách đã đến - nhận bàn");
        btnCheckIn.setFocusable(false);
        btnCheckIn.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnCheckInActionPerformed(evt);
            }
        });
        pnlResActions.add(btnCheckIn);

        pnlReservations.add(pnlResActions, java.awt.BorderLayout.SOUTH);

        tabRight.addTab("Đặt bàn", pnlReservations);

        splitMessages.setDividerLocation(200);
        splitMessages.setResizeWeight(0.3);
        pnlConvList.setBackground(new java.awt.Color(255, 255, 255));
        pnlConvList.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 8, 8, 4));
        pnlConvList.setLayout(new java.awt.BorderLayout(0, 6));

        lblConvHint.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        lblConvHint.setForeground(new java.awt.Color(44, 62, 80));
        lblConvHint.setText("Các bàn (bàn mới nhắn ở trên)");
        pnlConvList.add(lblConvHint, java.awt.BorderLayout.NORTH);

        tblConversations.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        tblConversations.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {

            },
            new String [] {
                "Bàn", "Tin gần nhất", "Giờ"
            }
        ) {
            boolean[] canEdit = new boolean [] {
                false, false, false
            };

            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return canEdit [columnIndex];
            }
        });
        tblConversations.setRowHeight(28);
        scrollConversations.setViewportView(tblConversations);

        pnlConvList.add(scrollConversations, java.awt.BorderLayout.CENTER);

        splitMessages.setLeftComponent(pnlConvList);

        pnlMessages.setBackground(new java.awt.Color(255, 255, 255));
        pnlMessages.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 4, 8, 8));
        pnlMessages.setLayout(new java.awt.BorderLayout(0, 8));

        pnlConvHeader.setOpaque(false);
        pnlConvHeader.setLayout(new java.awt.BorderLayout(8, 0));

        lblConvTitle.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        lblConvTitle.setForeground(new java.awt.Color(44, 62, 80));
        lblConvTitle.setText("Chọn một bàn bên trái để xem và trả lời tin nhắn");
        pnlConvHeader.add(lblConvTitle, java.awt.BorderLayout.CENTER);

        cboChatScope.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        cboChatScope.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "Lượt khách hiện tại", "Hôm nay", "7 ngày qua" }));
        cboChatScope.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cboChatScopeActionPerformed(evt);
            }
        });
        pnlConvHeader.add(cboChatScope, java.awt.BorderLayout.EAST);

        pnlMessages.add(pnlConvHeader, java.awt.BorderLayout.NORTH);

        pnlChatHost.setBackground(new java.awt.Color(255, 255, 255));
        pnlChatHost.setLayout(new java.awt.BorderLayout());

        pnlMessages.add(pnlChatHost, java.awt.BorderLayout.CENTER);

        pnlReply.setOpaque(false);
        pnlReply.setLayout(new java.awt.BorderLayout(6, 0));

        txtReply.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        txtReply.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                txtReplyActionPerformed(evt);
            }
        });
        pnlReply.add(txtReply, java.awt.BorderLayout.CENTER);

        btnSendReply.setBackground(new java.awt.Color(169, 29, 34));
        btnSendReply.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnSendReply.setForeground(new java.awt.Color(255, 255, 255));
        btnSendReply.setText("Gửi");
        btnSendReply.setFocusable(false);
        btnSendReply.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSendReplyActionPerformed(evt);
            }
        });
        pnlReply.add(btnSendReply, java.awt.BorderLayout.EAST);

        pnlMessages.add(pnlReply, java.awt.BorderLayout.SOUTH);

        splitMessages.setRightComponent(pnlMessages);

        tabRight.addTab("Tin nhắn", splitMessages);

        pnlRight.add(tabRight, java.awt.BorderLayout.CENTER);

        splitMain.setRightComponent(pnlRight);

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

    private void cboAreaActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cboAreaActionPerformed
        if (!loadingTables) rebuildMap();
    }//GEN-LAST:event_cboAreaActionPerformed

    private void btnWalkInActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnWalkInActionPerformed
        new WalkInDialog(this).setVisible(true);
    }//GEN-LAST:event_btnWalkInActionPerformed

    private void btnNewReservationTopActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnNewReservationTopActionPerformed
        tabRight.setSelectedComponent(pnlReservations);
        openReservation(null, null);
    }//GEN-LAST:event_btnNewReservationTopActionPerformed

    private void btnRefreshTablesActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnRefreshTablesActionPerformed
        requestTables();
        requestReservations();
    }//GEN-LAST:event_btnRefreshTablesActionPerformed

    private void cboTableFilterActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cboTableFilterActionPerformed
        applyOrderFilter();
    }//GEN-LAST:event_cboTableFilterActionPerformed

    private void cboStatusFilterActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cboStatusFilterActionPerformed
        applyOrderFilter();
    }//GEN-LAST:event_cboStatusFilterActionPerformed

    private void btnOpenOrderTableActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnOpenOrderTableActionPerformed
        openSelectedOrderTable();
    }//GEN-LAST:event_btnOpenOrderTableActionPerformed

    private void btnCancelOrderActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnCancelOrderActionPerformed
        updateSelectedOrder(Order.STATUS_CANCELLED);
    }//GEN-LAST:event_btnCancelOrderActionPerformed

    private void btnServedActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnServedActionPerformed
        updateSelectedOrder(Order.STATUS_SERVED);
    }//GEN-LAST:event_btnServedActionPerformed

    private void btnResPrevActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnResPrevActionPerformed
        shiftResDate(-1);
    }//GEN-LAST:event_btnResPrevActionPerformed

    private void txtResDateActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_txtResDateActionPerformed
        try {
            DAY.setLenient(false);
            setResDate(DAY.parse(txtResDate.getText().trim()));
        } catch (java.text.ParseException ex) {
            toast("Ngày không hợp lệ, nhập theo dạng dd/MM/yyyy.");
            txtResDate.setText(DAY.format(resDate));
        }
    }//GEN-LAST:event_txtResDateActionPerformed

    private void btnResNextActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnResNextActionPerformed
        shiftResDate(1);
    }//GEN-LAST:event_btnResNextActionPerformed

    private void btnResTodayActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnResTodayActionPerformed
        setResDate(new java.util.Date());
    }//GEN-LAST:event_btnResTodayActionPerformed

    private void btnNewReservationActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnNewReservationActionPerformed
        openReservation(null, null);
    }//GEN-LAST:event_btnNewReservationActionPerformed

    private void btnEditReservationActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnEditReservationActionPerformed
        editSelectedReservation();
    }//GEN-LAST:event_btnEditReservationActionPerformed

    private void btnCancelReservationActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnCancelReservationActionPerformed
        changeReservationStatus(Reservation.STATUS_CANCELLED);
    }//GEN-LAST:event_btnCancelReservationActionPerformed

    private void btnNoShowActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnNoShowActionPerformed
        changeReservationStatus(Reservation.STATUS_NO_SHOW);
    }//GEN-LAST:event_btnNoShowActionPerformed

    private void btnCheckInActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnCheckInActionPerformed
        checkInSelected();
    }//GEN-LAST:event_btnCheckInActionPerformed

    private void cboChatScopeActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cboChatScopeActionPerformed
        if (chatTable != null) openConversation(chatTable);
    }//GEN-LAST:event_cboChatScopeActionPerformed

    private void txtReplyActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_txtReplyActionPerformed
        sendReply();
    }//GEN-LAST:event_txtReplyActionPerformed

    private void btnSendReplyActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSendReplyActionPerformed
        sendReply();
    }//GEN-LAST:event_btnSendReplyActionPerformed

    public static void main(String args[]) {
        HaidilaoNetworkPOS.applyLookAndFeel();
        java.awt.EventQueue.invokeLater(() -> new ReceptionFrame().startWithLogin());
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JPanel pnlHeader;
    private javax.swing.JPanel pnlHeaderLeft;
    private javax.swing.JLabel lblTitle;
    private javax.swing.JLabel lblStaff;
    private javax.swing.JPanel pnlStats;
    private javax.swing.JLabel lblStatFreeTables;
    private javax.swing.JLabel lblStatFreeSeats;
    private javax.swing.JLabel lblStatGuests;
    private javax.swing.JLabel lblStatCleaning;
    private javax.swing.JLabel lblStatReservations;
    private javax.swing.JPanel pnlHeaderRight;
    private javax.swing.JLabel lblConnection;
    private javax.swing.JLabel lblClock;
    private javax.swing.JSplitPane splitMain;
    private javax.swing.JPanel pnlMap;
    private javax.swing.JPanel pnlMapTools;
    private javax.swing.JLabel lblArea;
    private javax.swing.JComboBox<String> cboArea;
    private javax.swing.JButton btnWalkIn;
    private javax.swing.JButton btnNewReservationTop;
    private javax.swing.JButton btnRefreshTables;
    private javax.swing.JScrollPane scrollMap;
    private javax.swing.JPanel pnlTableMap;
    private javax.swing.JLabel lblLegend;
    private javax.swing.JPanel pnlRight;
    private javax.swing.JTabbedPane tabRight;
    private javax.swing.JPanel pnlOrders;
    private javax.swing.JPanel pnlOrderFilter;
    private javax.swing.JLabel lblTableFilter;
    private javax.swing.JComboBox<String> cboTableFilter;
    private javax.swing.JLabel lblStatusFilter;
    private javax.swing.JComboBox<String> cboStatusFilter;
    private javax.swing.JScrollPane scrollOrders;
    private javax.swing.JTable tblOrders;
    private javax.swing.JPanel pnlOrderActions;
    private javax.swing.JButton btnOpenOrderTable;
    private javax.swing.JButton btnCancelOrder;
    private javax.swing.JButton btnServed;
    private javax.swing.JPanel pnlReservations;
    private javax.swing.JPanel pnlResTools;
    private javax.swing.JLabel lblResDate;
    private javax.swing.JButton btnResPrev;
    private javax.swing.JTextField txtResDate;
    private javax.swing.JButton btnResNext;
    private javax.swing.JButton btnResToday;
    private javax.swing.JButton btnNewReservation;
    private javax.swing.JScrollPane scrollReservations;
    private javax.swing.JTable tblReservations;
    private javax.swing.JPanel pnlResActions;
    private javax.swing.JButton btnEditReservation;
    private javax.swing.JButton btnCancelReservation;
    private javax.swing.JButton btnNoShow;
    private javax.swing.JButton btnCheckIn;
    private javax.swing.JSplitPane splitMessages;
    private javax.swing.JPanel pnlConvList;
    private javax.swing.JLabel lblConvHint;
    private javax.swing.JScrollPane scrollConversations;
    private javax.swing.JTable tblConversations;
    private javax.swing.JPanel pnlMessages;
    private javax.swing.JPanel pnlConvHeader;
    private javax.swing.JLabel lblConvTitle;
    private javax.swing.JComboBox<String> cboChatScope;
    private javax.swing.JPanel pnlChatHost;
    private javax.swing.JPanel pnlReply;
    private javax.swing.JTextField txtReply;
    private javax.swing.JButton btnSendReply;
    private javax.swing.JPanel pnlStatusBar;
    private javax.swing.JLabel lblToast;
    // End of variables declaration//GEN-END:variables
}
