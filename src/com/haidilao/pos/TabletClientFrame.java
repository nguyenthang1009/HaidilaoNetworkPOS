/*
 * TabletClientFrame.java
 */
package com.haidilao.pos;

import com.haidilao.pos.client.ClientEventListener;
import com.haidilao.pos.client.PosClient;
import com.haidilao.pos.database.TableDao;
import com.haidilao.pos.model.MenuItem;
import com.haidilao.pos.model.Order;
import com.haidilao.pos.model.OrderLine;
import com.haidilao.pos.protocol.NetworkPacket;
import com.haidilao.pos.protocol.ProtocolConstants;
import com.haidilao.pos.ui.ChatPanel;
import com.haidilao.pos.ui.ResponsiveGrid;
import com.haidilao.pos.ui.Theme;
import com.haidilao.pos.util.ImageUtil;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 * Màn hình Tablet tại bàn: thực đơn theo danh mục + combo, giỏ hàng có ghi chú, đơn của bàn (trạng thái trực tiếp),
 * nhắn tin với Lễ tân (chỉ tin của lượt khách hiện tại), gọi phục vụ và yêu cầu thanh toán.
 */
public class TabletClientFrame extends javax.swing.JFrame implements ClientEventListener {

    private final String tableId;
    private final String tableName;
    private PosClient client;
    private ChatPanel chat;
    private DefaultTableModel cartModel;
    private DefaultTableModel myOrdersModel;
    private final List<CartEntry> cartEntries = new ArrayList<>();   // song song với các dòng của bảng giỏ hàng
    private final List<MenuItem> receivingMenu = new ArrayList<>();  // thực đơn đang nhận dở từ Server
    private final List<String[]> receivingCombos = new ArrayList<>(); // {mã, tên, giá, mô tả, các món, ảnh base64}
    private boolean orderPending = false;  // đã gửi đơn, đang chờ ORDER_ACCEPTED / ORDER_REJECTED
    private boolean sessionOpen = false;   // Lễ tân đã mở bàn (xếp bàn) cho khách chưa; chưa mở thì chưa gọi món được
    private int receptionOnline = -1;
    private int unreadChat = 0;
    private int myOrderCount = 0;
    private PaymentDialog paymentDialog;

    /**
     * Một dòng trong giỏ hàng: món lẻ (M) hoặc combo (C), mã trong database và đơn giá
     */
    private static final class CartEntry {
        final char type;
        final int refId;
        final String name;
        final long unitPrice;

        CartEntry(char type, int refId, String name, long unitPrice) {
            this.type = type;
            this.refId = refId;
            this.name = name;
            this.unitPrice = unitPrice;
        }
    }

    public TabletClientFrame(String tableId, String tableName) {
        this.tableId = tableId;
        this.tableName = (tableName == null || tableName.isEmpty()) ? TableDao.defaultTableName(tableId) : tableName;
        initComponents();
        initCustomLogic();
    }

    private void initCustomLogic() {
        getContentPane().setBackground(Theme.BG);
        setTitle("Gọi món tại bàn - " + tableName);
        lblTableName.setText(tableName.toUpperCase());
        cartModel = (DefaultTableModel) tblCart.getModel();
        myOrdersModel = (DefaultTableModel) tblMyOrders.getModel();
        Theme.styleTable(tblCart, -1);
        Theme.styleTable(tblMyOrders, 4);
        int[] cartWidths = {40, 170, 40, 90, 150};
        for (int i = 0; i < cartWidths.length; i++) tblCart.getColumnModel().getColumn(i).setPreferredWidth(cartWidths[i]);
        javax.swing.table.DefaultTableCellRenderer center = new javax.swing.table.DefaultTableCellRenderer();
        center.setHorizontalAlignment(SwingConstants.CENTER);
        tblCart.getColumnModel().getColumn(0).setCellRenderer(center);
        tblCart.getColumnModel().getColumn(2).setCellRenderer(center);
        tblMyOrders.getColumnModel().getColumn(0).setPreferredWidth(50);
        tblMyOrders.getColumnModel().getColumn(1).setPreferredWidth(50);
        tblMyOrders.getColumnModel().getColumn(2).setPreferredWidth(260);
        tblMyOrders.getColumnModel().getColumn(3).setPreferredWidth(90);
        tblMyOrders.getColumnModel().getColumn(4).setPreferredWidth(100);
        tblCart.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        chat = ChatPanel.installInto(pnlChatHost);
        tabMenu.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        ResponsiveGrid.install(scrollCombo, pnlCombo, 230, 12);
        tabRight.addChangeListener(e -> {
            if (tabRight.getSelectedComponent() == pnlChat) {
                unreadChat = 0;
                updateTabTitles();
            }
        });
        new Timer(1000, e -> lblClock.setText(new SimpleDateFormat("HH:mm  ·  dd/MM/yyyy").format(new Date()))).start();
        setMinimumSize(new Dimension(1000, 640));
        Theme.fitToScreen(this, 0.92);
        updateTabTitles();
        updateOrderAvailability();
    }

    /** Kết nối máy chủ (tự tìm trong mạng Wi-Fi, không cần nhập IP) */
    public void start() {
        client = new PosClient(null, ProtocolConstants.SERVER_PORT, "TABLET", tableId, ProtocolConstants.KEY_TABLET);
        client.setListener(this);
        client.connect();
    }

    private boolean isOnline() {
        return client != null && client.isConnected();
    }

    private void send(String msg) {
        if (isOnline()) client.send(msg);
    }

    private void toast(String text) {
        Theme.flash(lblToast, text, 8000);
    }

    private void setConnection(String text, Color color) {
        lblConnection.setText(text);
        lblConnection.setForeground(color);
    }

    private void updateTabTitles() {
        tabRight.setTitleAt(0, "Giỏ hàng" + (cartEntries.isEmpty() ? "" : " (" + cartEntries.size() + ")"));
        tabRight.setTitleAt(1, "Đơn của bàn" + (myOrderCount == 0 ? "" : " (" + myOrderCount + ")"));
        tabRight.setTitleAt(2, unreadChat > 0 ? "Nhắn nhân viên  ● " + unreadChat : "Nhắn nhân viên");
    }

    // ------------------------------------------------------------------ thực đơn

    /**
     * Vẽ thực đơn: mỗi danh mục một tab (theo danh mục quản lý tạo trong database), combo ở tab cuối
     */
    private void showMenu(List<MenuItem> items, List<String[]> combos) {
        while (tabMenu.getTabCount() > 1) {
            tabMenu.removeTabAt(0); // giữ lại tab Combo (cuối cùng)
        }
        Map<String, List<MenuItem>> byCategory = new TreeMap<>();
        for (MenuItem m : items) {
            byCategory.computeIfAbsent(m.getCategory(), k -> new ArrayList<>()).add(m);
        }
        int index = 0;
        for (Map.Entry<String, List<MenuItem>> e : byCategory.entrySet()) {
            JPanel grid = new JPanel();
            grid.setBackground(Theme.BG);
            grid.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
            JScrollPane sp = new JScrollPane(grid);
            sp.setBorder(null);
            ResponsiveGrid.install(sp, grid, 230, 12);
            for (MenuItem m : e.getValue()) {
                grid.add(createDishCard(OrderLine.TYPE_ITEM, m.getItemId(), m.getItemName(), m.getPrice(), m.getImageData(), null));
            }
            String title = e.getKey().replaceFirst("^\\d+\\.\\s*", "");
            tabMenu.insertTab(title + " (" + e.getValue().size() + ")", null, sp, null, index++);
            ResponsiveGrid.refresh(grid);
        }
        pnlCombo.removeAll();
        for (String[] c : combos) {
            String detail = c[4].isEmpty() ? c[3] : "Gồm: " + c[4];
            byte[] image = null;
            try {
                if (c.length > 5 && !c[5].isEmpty()) image = Base64.getDecoder().decode(c[5]);
            } catch (IllegalArgumentException ignored) {}
            pnlCombo.add(createDishCard(OrderLine.TYPE_COMBO, NetworkPacket.parseInt(c[0], 0), c[1], NetworkPacket.parseInt(c[2], 0), image, detail));
        }
        tabMenu.setTitleAt(tabMenu.getTabCount() - 1, "Combo ưu đãi (" + combos.size() + ")");
        ResponsiveGrid.refresh(pnlCombo);
        if (tabMenu.getTabCount() > 0) tabMenu.setSelectedIndex(0);
        updateOrderAvailability();
        if (items.isEmpty() && combos.isEmpty()) lblMenuHint.setText("Thực đơn đang trống, vui lòng liên hệ nhân viên.");
    }

    private JPanel createDishCard(char type, int refId, String name, long price, byte[] imageData, String detail) {
        JPanel card = new JPanel(new BorderLayout(4, 6));
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER), BorderFactory.createEmptyBorder(8, 8, 8, 8)));

        JLabel lblImage = new JLabel("", SwingConstants.CENTER);
        lblImage.setPreferredSize(new Dimension(ImageUtil.DISPLAY_WIDTH, ImageUtil.DISPLAY_HEIGHT));
        ImageIcon icon = ImageUtil.toIcon(imageData);
        if (icon != null) {
            lblImage.setIcon(icon);
        } else {
            lblImage.setText(type == OrderLine.TYPE_COMBO ? "COMBO" : "Chưa có ảnh");
            lblImage.setFont(type == OrderLine.TYPE_COMBO ? new Font("Segoe UI", Font.BOLD, 22) : Theme.FONT_SMALL);
            lblImage.setForeground(type == OrderLine.TYPE_COMBO ? Theme.PRIMARY : Theme.MUTED);
            lblImage.setOpaque(true);
            lblImage.setBackground(type == OrderLine.TYPE_COMBO ? new Color(253, 237, 236) : new Color(236, 240, 241));
        }
        String extra = (detail == null || detail.isEmpty()) ? ""
                : "<br><span style='color:#7f8c8d;font-size:9px'>" + escapeHtml(detail) + "</span>";
        JLabel lblTitle = new JLabel("<html><div style='width:165px'><b style='color:#2c3e50'>" + escapeHtml(name) + "</b>" + extra + "</div></html>");
        lblTitle.setFont(Theme.FONT);
        lblTitle.setVerticalAlignment(SwingConstants.TOP);

        JLabel lblPrice = new JLabel(Theme.money(price));
        lblPrice.setFont(new Font("Segoe UI", Font.BOLD, 14));
        lblPrice.setForeground(Theme.PRIMARY);
        JButton btnAdd = new JButton("+ Thêm");
        btnAdd.setBackground(Theme.PRIMARY);
        btnAdd.setForeground(Color.WHITE);
        btnAdd.setFont(Theme.FONT_BOLD);
        btnAdd.setFocusable(false);
        btnAdd.addActionListener(e -> addToCart(type, refId, name, price));
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.add(lblPrice, BorderLayout.WEST);
        bottom.add(btnAdd, BorderLayout.EAST);

        card.add(lblImage, BorderLayout.NORTH);
        card.add(lblTitle, BorderLayout.CENTER);
        card.add(bottom, BorderLayout.SOUTH);
        return card;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ------------------------------------------------------------------ giỏ hàng

    private void addToCart(char type, int refId, String name, long price) {
        for (int i = 0; i < cartEntries.size(); i++) {
            CartEntry e = cartEntries.get(i);
            if (e.type == type && e.refId == refId) {
                setQuantity(i, quantityAt(i) + 1);
                toast("Đã thêm " + name + " (x" + quantityAt(i) + ")");
                return;
            }
        }
        String label = (type == OrderLine.TYPE_COMBO) ? "[Combo] " + name : name;
        cartModel.addRow(new Object[]{cartModel.getRowCount() + 1, label, 1, Theme.money(price), ""});
        cartEntries.add(new CartEntry(type, refId, name, price));
        recalcTotal();
        toast("Đã thêm " + name + " vào giỏ hàng");
    }

    private int quantityAt(int row) {
        return Integer.parseInt(cartModel.getValueAt(row, 2).toString());
    }

    private void setQuantity(int row, int qty) {
        if (qty <= 0) {
            cartModel.removeRow(row);
            cartEntries.remove(row);
            renumberCart();
        } else {
            qty = Math.min(qty, ProtocolConstants.MAX_LINE_QUANTITY);
            cartModel.setValueAt(qty, row, 2);
            cartModel.setValueAt(Theme.money(qty * cartEntries.get(row).unitPrice), row, 3);
        }
        recalcTotal();
    }

    private void changeSelectedQuantity(int delta) {
        int row = tblCart.getSelectedRow();
        if (row < 0) {
            toast("Chọn một món trong giỏ hàng trước.");
            return;
        }
        if (tblCart.isEditing()) tblCart.getCellEditor().stopCellEditing();
        setQuantity(row, quantityAt(row) + delta);
        if (row < cartModel.getRowCount()) tblCart.setRowSelectionInterval(row, row);
    }

    /** Đánh lại số thứ tự 1, 2, 3... sau khi bỏ món khỏi giỏ */
    private void renumberCart() {
        for (int i = 0; i < cartModel.getRowCount(); i++) cartModel.setValueAt(i + 1, i, 0);
    }

    private void recalcTotal() {
        long total = 0;
        for (int i = 0; i < cartEntries.size(); i++) {
            total += cartEntries.get(i).unitPrice * quantityAt(i);
        }
        lblCartTotal.setText("Tổng giỏ hàng: " + Theme.money(total));
        updateTabTitles();
    }

    private void clearCart() {
        if (tblCart.isEditing()) tblCart.getCellEditor().cancelCellEditing();
        cartModel.setRowCount(0);
        cartEntries.clear();
        txtOrderNote.setText("");
        recalcTotal();
    }

    private void sendOrder() {
        if (cartEntries.isEmpty()) {
            toast("Giỏ hàng đang trống, quý khách hãy chọn món trước.");
            return;
        }
        if (orderPending) {
            toast("Đơn trước đang được gửi, vui lòng chờ trong giây lát.");
            return;
        }
        if (!sessionOpen) {
            JOptionPane.showMessageDialog(this, "Bàn chưa được mở.\nQuý khách vui lòng đến quầy Lễ tân để được xếp bàn trước khi gọi món.",
                    "Chưa mở bàn", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!isOnline()) {
            JOptionPane.showMessageDialog(this, "Tablet đang mất kết nối máy chủ, chưa gửi được đơn.\nVui lòng chờ kết nối lại hoặc gọi nhân viên.",
                    "Chưa kết nối", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (tblCart.isEditing()) tblCart.getCellEditor().stopCellEditing(); // lưu ghi chú đang gõ dở
        // Chỉ gửi mã món + số lượng + ghi chú. Giá do Server tự tính từ database.
        List<OrderLine> lines = new ArrayList<>();
        for (int i = 0; i < cartEntries.size(); i++) {
            CartEntry e = cartEntries.get(i);
            Object note = cartModel.getValueAt(i, 4);
            lines.add(new OrderLine(e.type, e.refId, e.name, quantityAt(i), e.unitPrice, note == null ? "" : note.toString().trim()));
        }
        orderPending = true;
        btnSendOrder.setEnabled(false);
        client.send(NetworkPacket.buildOrder(tableId, txtOrderNote.getText().trim(), lines));
        toast("Đang gửi đơn xuống bếp...");
    }

    // ------------------------------------------------------------------ dịch vụ, nhắn tin, thanh toán

    private void showServiceMenu() {
        JPopupMenu menu = new JPopupMenu();
        String[] reasons = {"Gọi nhân viên đến bàn", "Châm thêm nước lẩu", "Xin khăn ướt / tạp dề / dây buộc tóc",
                "Thêm bát, đũa, muỗng", "Thêm nước chấm, gia vị"};
        for (String r : reasons) {
            JMenuItem item = new JMenuItem(r);
            item.setFont(new Font("Segoe UI", Font.PLAIN, 14));
            item.addActionListener(e -> ring(r));
            menu.add(item);
        }
        menu.show(btnCallStaff, 0, btnCallStaff.getHeight());
    }

    private void ring(String reason) {
        if (!isOnline()) {
            toast("Mất kết nối máy chủ, vui lòng giơ tay gọi nhân viên.");
            return;
        }
        client.send(NetworkPacket.buildRing(tableId, reason));
        toast("Đã gọi nhân viên: " + reason);
    }

    private void sendChat() {
        String msg = txtChatInput.getText().trim();
        if (msg.isEmpty()) return;
        if (!isOnline()) {
            toast("Mất kết nối máy chủ, chưa gửi được tin nhắn.");
            return;
        }
        client.send(NetworkPacket.buildChatToReception(tableId, msg));
        chat.addMessage("", msg, new SimpleDateFormat("HH:mm").format(new Date()), true);
        txtChatInput.setText("");
    }

    private void openPayment() {
        if (!isOnline()) {
            toast("Mất kết nối máy chủ, vui lòng gọi nhân viên để thanh toán.");
            return;
        }
        if (myOrderCount == 0) {
            toast("Bàn chưa gọi món nào nên chưa cần thanh toán.");
            return;
        }
        paymentDialog = new PaymentDialog(this, client, tableName);
        paymentDialog.setVisible(true);
        paymentDialog = null;
    }

    /**
     * Khách phải được Lễ tân xếp bàn trước khi gọi món: bàn chưa mở thì vẫn xem thực đơn, chọn món vào giỏ,
     * nhưng nút gửi đơn bị khóa và có lời nhắc ra quầy.
     */
    private void updateOrderAvailability() {
        btnSendOrder.setEnabled(sessionOpen && !orderPending);
        btnSendOrder.setText(sessionOpen ? "GỬI ĐƠN XUỐNG BẾP" : "MỜI RA QUẦY LỄ TÂN");
        btnPayBill.setEnabled(sessionOpen);
        lblMenuHint.setForeground(sessionOpen ? Theme.MUTED : Theme.PRIMARY);
        lblMenuHint.setText(sessionOpen
                ? "Chọn món hoặc combo, bấm \"+ Thêm\" để cho vào giỏ hàng"
                : "Bàn chưa được mở. Quý khách vui lòng đến quầy Lễ tân để được xếp bàn (ghi tên, số khách) rồi mới gọi món.");
    }

    private void resetForNewGuest(String message) {
        sessionOpen = false;
        updateOrderAvailability();
        clearCart();
        chat.clear();
        myOrdersModel.setRowCount(0);
        myOrderCount = 0;
        lblMyOrdersTotal.setText("Tạm tính: 0 đ");
        lblGreeting.setText("Mời quý khách đến quầy Lễ tân để được xếp bàn");
        updateTabTitles();
        tabRight.setSelectedIndex(0);
        if (paymentDialog != null) paymentDialog.dispose();
        // Lời cảm ơn tự đóng sau 10 giây để màn hình sẵn sàng cho khách tiếp theo
        JOptionPane pane = new JOptionPane(message, JOptionPane.INFORMATION_MESSAGE);
        JDialog d = pane.createDialog(this, "Cảm ơn quý khách");
        Timer t = new Timer(10000, e -> d.dispose());
        t.setRepeats(false);
        t.start();
        d.setVisible(true);
    }

    // ------------------------------------------------------------------ ClientEventListener

    @Override
    public void onConnected() {
        SwingUtilities.invokeLater(() -> {
            setConnection("● Đã kết nối", new Color(170, 255, 190));
            orderPending = false;
            updateOrderAvailability();
            chat.clear();
        });
        send(NetworkPacket.buildGetMenu(tableId));
        send(NetworkPacket.buildGetHistory(tableId));
        send(NetworkPacket.buildGetMyOrders());
    }

    @Override
    public void onDisconnected(String reason) {
        SwingUtilities.invokeLater(() -> {
            setConnection("● Mất kết nối, đang tìm lại máy chủ...", Theme.GOLD);
            orderPending = false;
            updateOrderAvailability();
            receptionOnline = -1;
        });
    }

    @Override
    public void onAuthFailed(String reason) {
        SwingUtilities.invokeLater(() -> {
            setConnection("● Bị từ chối kết nối", Theme.GOLD);
            JOptionPane.showMessageDialog(this, reason + "\nVui lòng liên hệ nhân viên.", "Không thể kết nối hệ thống", JOptionPane.WARNING_MESSAGE);
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
        SwingUtilities.invokeLater(() -> handleMessage(raw));
    }

    private void handleMessage(String raw) {
        String[] f = NetworkPacket.split(raw);
        String cmd = f[0];
        String time = new SimpleDateFormat("HH:mm").format(new Date());
        switch (cmd) {
            case "SERVER_ACK":
                toast(f.length > 1 ? f[1] : "");
                break;
            case "CHAT_FROM_STAFF": {
                // CHAT_FROM_STAFF|TÊN_NHÂN_VIÊN|NỘI_DUNG (phục vụ của bàn hoặc Lễ tân)
                String sender = f.length > 1 ? NetworkPacket.dec(f[1]) : "Nhân viên";
                chat.addMessage(sender, f.length > 2 ? NetworkPacket.dec(f[2]) : "", time, false);
                Toolkit.getDefaultToolkit().beep();
                if (tabRight.getSelectedComponent() != pnlChat) {
                    unreadChat++;
                    updateTabTitles();
                    toast(sender + " vừa nhắn tin cho quý khách");
                }
                break;
            }
            case "CHAT_ACK":
                // CHAT_ACK|DELIVERED|NGƯỜI_NHẬN hoặc CHAT_ACK|STORED
                toast("DELIVERED".equals(f[1])
                        ? "Tin nhắn đã tới " + (f.length > 2 ? NetworkPacket.dec(f[2]) : "nhân viên")
                        : "Nhân viên chưa trực tuyến, tin nhắn đã được lưu lại");
                break;
            case "HISTORY":
                // HISTORY|THỜI_GIAN|NGƯỜI_GỬI|NGƯỜI_NHẬN|LOẠI|NỘI_DUNG|TÊN_NGƯỜI_GỬI (chỉ tin nhắn của lượt khách hiện tại)
                if (f.length >= 6 && "CHAT".equals(f[4])) {
                    boolean mine = tableId.equalsIgnoreCase(f[2]);
                    String staffName = f.length > 6 ? NetworkPacket.dec(f[6]) : "";
                    chat.addMessage(mine ? "" : (staffName.isEmpty() ? "Nhân viên" : staffName), f[5], f[1], mine);
                }
                break;
            case "RECEPTION_STATE":
                receptionOnline = NetworkPacket.parseInt(f[1], -1);
                setConnection(receptionOnline > 0 ? "● Đã kết nối · Lễ tân trực tuyến" : "● Đã kết nối · Lễ tân chưa vào ca",
                        new Color(170, 255, 190));
                break;
            case "SESSION_INFO": {
                // SESSION_INFO|TÊN_KHÁCH|SỐ_KHÁCH|PHỤC_VỤ|ĐÃ_MỞ_BÀN
                String customer = f.length > 1 ? NetworkPacket.dec(f[1]) : "";
                String waiter = f.length > 3 ? NetworkPacket.dec(f[3]) : "";
                sessionOpen = f.length > 4 && "1".equals(f[4]);
                lblChatHint.setText(waiter.isEmpty()
                        ? "Cần hỗ trợ? Nhắn nhân viên tại đây. Tin nhắn chỉ hiện trong lượt dùng bữa này."
                        : "Tin nhắn được gửi tới nhân viên phục vụ " + waiter + " của bàn (và quầy Lễ tân). Chỉ hiện trong lượt dùng bữa này.");
                if (sessionOpen) {
                    String greet = customer.isEmpty() ? "Chào mừng quý khách!" : "Xin chào " + customer + "!";
                    lblGreeting.setText(greet + (waiter.isEmpty() ? "" : "   Nhân viên phục vụ: " + waiter));
                } else {
                    lblGreeting.setText("Mời quý khách đến quầy Lễ tân để được xếp bàn");
                }
                updateOrderAvailability();
                break;
            }
            case "SESSION_CLOSED":
                resetForNewGuest("Bàn đã thanh toán " + Theme.money(f.length > 1 ? NetworkPacket.parseInt(f[1], 0) : 0)
                        + ".\nCảm ơn quý khách đã dùng bữa, hẹn gặp lại!");
                break;
            case "SESSION_CANCELLED":
                resetForNewGuest("Bàn đã được làm mới. Chào mừng quý khách!");
                break;
            case "SESSION_MOVED":
                resetForNewGuest("Quý khách đã được chuyển sang " + TableDao.defaultTableName(f.length > 1 ? f[1] : "")
                        + ".\nĐơn và tin nhắn đã chuyển theo sang bàn mới.");
                break;
            case "MENU_BEGIN":
                receivingMenu.clear();
                receivingCombos.clear();
                break;
            case "MENU_ITEM":
                // MENU_ITEM|MÃ_MÓN|DANH_MỤC|TÊN_MÓN|GIÁ|ẢNH_BASE64
                if (f.length >= 5) {
                    try {
                        MenuItem item = new MenuItem(Integer.parseInt(f[1]), f[2], f[3], Long.parseLong(f[4]), "AVAILABLE");
                        if (f.length >= 6 && !f[5].isEmpty()) item.setImageData(Base64.getDecoder().decode(f[5]));
                        receivingMenu.add(item);
                    } catch (IllegalArgumentException ignored) {}
                }
                break;
            case "COMBO_ITEM":
                if (f.length >= 6) {
                    receivingCombos.add(new String[]{f[1], NetworkPacket.dec(f[2]), f[3], NetworkPacket.dec(f[4]), NetworkPacket.dec(f[5]),
                            f.length > 6 ? f[6] : ""});
                }
                break;
            case "MENU_END":
                showMenu(new ArrayList<>(receivingMenu), new ArrayList<>(receivingCombos));
                break;
            case "MENU_UPDATED":
                send(NetworkPacket.buildGetMenu(tableId));
                toast("Thực đơn vừa được cập nhật");
                break;
            case "ORDER_ACCEPTED": {
                // ORDER_ACCEPTED|MÃ_ĐƠN|STT|TỔNG_TIỀN|TÓM_TẮT|BẾP_ĐANG_TRỰC
                orderPending = false;
                updateOrderAvailability();
                clearCart();
                toast("Đơn " + Order.formatOrderNo(NetworkPacket.parseInt(f[2], 0)) + " đã gửi xuống bếp ("
                        + Theme.money(NetworkPacket.parseInt(f[3], 0)) + "). Quý khách vui lòng chờ món nhé!");
                tabRight.setSelectedComponent(pnlMyOrders);
                send(NetworkPacket.buildGetMyOrders());
                break;
            }
            case "ORDER_REJECTED": {
                orderPending = false;
                updateOrderAvailability();
                String reason = f.length > 1 ? NetworkPacket.dec(f[1]) : "";
                JOptionPane.showMessageDialog(this, reason, "Đơn hàng chưa được gửi", JOptionPane.WARNING_MESSAGE);
                send(NetworkPacket.buildGetMenu(tableId)); // món có thể vừa hết, tải lại thực đơn
                break;
            }
            case "ORDER_STATUS": {
                // ORDER_STATUS|MÃ_ĐƠN|STT|TRẠNG_THÁI
                String status = f.length > 3 ? NetworkPacket.dec(f[3]) : "";
                if (Order.STATUS_DONE.equals(status)) Toolkit.getDefaultToolkit().beep();
                toast("Đơn " + Order.formatOrderNo(NetworkPacket.parseInt(f[2], 0)) + ": " + describeStatus(status));
                send(NetworkPacket.buildGetMyOrders());
                break;
            }
            case "ORDERS_CHANGED":
                send(NetworkPacket.buildGetMyOrders());
                break;
            case "MY_ORDER":
                // MY_ORDER|MÃ_ĐƠN|STT|GIỜ|TÓM_TẮT|TỔNG_TIỀN|TRẠNG_THÁI, gom lại rồi vẽ khi nhận MY_ORDERS_END
                if (f.length >= 7) {
                    receivingOrders.add(new Object[]{Order.formatOrderNo(NetworkPacket.parseInt(f[2], 0)), f[3], NetworkPacket.dec(f[4]),
                            Theme.money(NetworkPacket.parseInt(f[5], 0)), NetworkPacket.dec(f[6])});
                }
                break;
            case "MY_ORDERS_END":
                myOrdersModel.setRowCount(0);
                for (int i = receivingOrders.size() - 1; i >= 0; i--) myOrdersModel.addRow(receivingOrders.get(i)); // mới nhất lên đầu
                myOrderCount = receivingOrders.size();
                receivingOrders.clear();
                lblMyOrdersTotal.setText("Tạm tính: " + Theme.money(f.length > 1 ? NetworkPacket.parseInt(f[1], 0) : 0));
                updateTabTitles();
                break;
            case "BILL_PREVIEW":
            case "PAY_REQUEST_OK":
            case "PAY_REQUEST_FAIL":
                if (paymentDialog != null) paymentDialog.onServerMessage(f);
                if ("PAY_REQUEST_OK".equals(cmd)) toast("Đã báo nhân viên, quý khách vui lòng chờ trong giây lát.");
                break;
            default:
                break;
        }
    }

    private final List<Object[]> receivingOrders = new ArrayList<>();

    /** Câu thông báo thân thiện cho khách theo trạng thái đơn */
    private static String describeStatus(String status) {
        switch (status) {
            case Order.STATUS_COOKING: return "bếp đang chế biến";
            case Order.STATUS_DONE: return "món đã xong, nhân viên đang mang ra";
            case Order.STATUS_SERVED: return "đã phục vụ, chúc quý khách ngon miệng!";
            case Order.STATUS_CANCELLED: return "đã bị hủy, vui lòng nhắn Lễ tân nếu cần hỗ trợ";
            default: return status;
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
        java.awt.GridBagConstraints gridBagConstraints;

        pnlHeader = new javax.swing.JPanel();
        pnlHeaderLeft = new javax.swing.JPanel();
        lblTableName = new javax.swing.JLabel();
        lblGreeting = new javax.swing.JLabel();
        pnlHeaderRight = new javax.swing.JPanel();
        lblConnection = new javax.swing.JLabel();
        btnCallStaff = new javax.swing.JButton();
        btnPayBill = new javax.swing.JButton();
        splitMain = new javax.swing.JSplitPane();
        pnlMenu = new javax.swing.JPanel();
        lblMenuHint = new javax.swing.JLabel();
        tabMenu = new javax.swing.JTabbedPane();
        scrollCombo = new javax.swing.JScrollPane();
        pnlCombo = new javax.swing.JPanel();
        pnlRight = new javax.swing.JPanel();
        tabRight = new javax.swing.JTabbedPane();
        pnlCart = new javax.swing.JPanel();
        pnlCartActions = new javax.swing.JPanel();
        btnMinus = new javax.swing.JButton();
        btnPlus = new javax.swing.JButton();
        btnRemoveLine = new javax.swing.JButton();
        lblCartHint = new javax.swing.JLabel();
        scrollCart = new javax.swing.JScrollPane();
        tblCart = new javax.swing.JTable();
        pnlCartBottom = new javax.swing.JPanel();
        lblOrderNote = new javax.swing.JLabel();
        txtOrderNote = new javax.swing.JTextField();
        lblCartTotal = new javax.swing.JLabel();
        pnlCartButtons = new javax.swing.JPanel();
        btnClearCart = new javax.swing.JButton();
        btnSendOrder = new javax.swing.JButton();
        pnlMyOrders = new javax.swing.JPanel();
        scrollMyOrders = new javax.swing.JScrollPane();
        tblMyOrders = new javax.swing.JTable();
        pnlMyOrdersBottom = new javax.swing.JPanel();
        lblMyOrdersTotal = new javax.swing.JLabel();
        lblMyOrdersHint = new javax.swing.JLabel();
        pnlChat = new javax.swing.JPanel();
        lblChatHint = new javax.swing.JLabel();
        pnlChatHost = new javax.swing.JPanel();
        pnlChatInput = new javax.swing.JPanel();
        txtChatInput = new javax.swing.JTextField();
        btnSendChat = new javax.swing.JButton();
        pnlStatusBar = new javax.swing.JPanel();
        lblToast = new javax.swing.JLabel();
        lblClock = new javax.swing.JLabel();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("Gọi món tại bàn");
        getContentPane().setLayout(new java.awt.BorderLayout());

        pnlHeader.setBackground(new java.awt.Color(169, 29, 34));
        pnlHeader.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 16, 10, 16));
        pnlHeader.setLayout(new java.awt.BorderLayout(12, 0));

        pnlHeaderLeft.setOpaque(false);
        pnlHeaderLeft.setLayout(new java.awt.GridLayout(2, 1, 0, 0));

        lblTableName.setFont(new java.awt.Font("Segoe UI", 1, 20)); // NOI18N
        lblTableName.setForeground(new java.awt.Color(255, 255, 255));
        lblTableName.setText("BÀN");
        pnlHeaderLeft.add(lblTableName);

        lblGreeting.setFont(new java.awt.Font("Segoe UI", 0, 12)); // NOI18N
        lblGreeting.setForeground(new java.awt.Color(245, 214, 214));
        lblGreeting.setText("Chào mừng quý khách!");
        pnlHeaderLeft.add(lblGreeting);

        pnlHeader.add(pnlHeaderLeft, java.awt.BorderLayout.WEST);

        pnlHeaderRight.setOpaque(false);
        pnlHeaderRight.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 10, 4));

        lblConnection.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        lblConnection.setForeground(new java.awt.Color(241, 196, 15));
        lblConnection.setText("● Đang tìm máy chủ...");
        pnlHeaderRight.add(lblConnection);

        btnCallStaff.setBackground(new java.awt.Color(241, 196, 15));
        btnCallStaff.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        btnCallStaff.setForeground(new java.awt.Color(44, 62, 80));
        btnCallStaff.setText("Gọi phục vụ");
        btnCallStaff.setFocusable(false);
        btnCallStaff.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnCallStaffActionPerformed(evt);
            }
        });
        pnlHeaderRight.add(btnCallStaff);

        btnPayBill.setBackground(new java.awt.Color(255, 255, 255));
        btnPayBill.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        btnPayBill.setForeground(new java.awt.Color(169, 29, 34));
        btnPayBill.setText("Thanh toán");
        btnPayBill.setFocusable(false);
        btnPayBill.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnPayBillActionPerformed(evt);
            }
        });
        pnlHeaderRight.add(btnPayBill);

        pnlHeader.add(pnlHeaderRight, java.awt.BorderLayout.EAST);

        getContentPane().add(pnlHeader, java.awt.BorderLayout.NORTH);

        splitMain.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 0));
        splitMain.setDividerLocation(760);
        splitMain.setResizeWeight(0.62);
        pnlMenu.setBackground(new java.awt.Color(244, 245, 247));
        pnlMenu.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 4));
        pnlMenu.setLayout(new java.awt.BorderLayout(0, 6));

        lblMenuHint.setFont(new java.awt.Font("Segoe UI", 0, 12)); // NOI18N
        lblMenuHint.setForeground(new java.awt.Color(127, 140, 141));
        lblMenuHint.setText("Chọn món hoặc combo, bấm \"+ Thêm\" để cho vào giỏ hàng");
        pnlMenu.add(lblMenuHint, java.awt.BorderLayout.NORTH);

        tabMenu.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        pnlCombo.setBackground(new java.awt.Color(244, 245, 247));
        pnlCombo.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 6, 6, 6));
        pnlCombo.setLayout(new java.awt.GridLayout(0, 2, 12, 12));

        scrollCombo.setViewportView(pnlCombo);

        tabMenu.addTab("Combo ưu đãi", scrollCombo);

        pnlMenu.add(tabMenu, java.awt.BorderLayout.CENTER);

        splitMain.setLeftComponent(pnlMenu);

        pnlRight.setBackground(new java.awt.Color(244, 245, 247));
        pnlRight.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 4, 10, 10));
        pnlRight.setLayout(new java.awt.BorderLayout());

        tabRight.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        pnlCart.setBackground(new java.awt.Color(255, 255, 255));
        pnlCart.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10));
        pnlCart.setLayout(new java.awt.BorderLayout(0, 8));

        pnlCartActions.setOpaque(false);
        pnlCartActions.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));

        btnMinus.setBackground(new java.awt.Color(236, 240, 241));
        btnMinus.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        btnMinus.setForeground(new java.awt.Color(44, 62, 80));
        btnMinus.setText("-");
        btnMinus.setFocusable(false);
        btnMinus.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnMinusActionPerformed(evt);
            }
        });
        pnlCartActions.add(btnMinus);

        btnPlus.setBackground(new java.awt.Color(236, 240, 241));
        btnPlus.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        btnPlus.setForeground(new java.awt.Color(44, 62, 80));
        btnPlus.setText("+");
        btnPlus.setFocusable(false);
        btnPlus.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnPlusActionPerformed(evt);
            }
        });
        pnlCartActions.add(btnPlus);

        btnRemoveLine.setBackground(new java.awt.Color(236, 240, 241));
        btnRemoveLine.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnRemoveLine.setForeground(new java.awt.Color(44, 62, 80));
        btnRemoveLine.setText("Bỏ món");
        btnRemoveLine.setFocusable(false);
        btnRemoveLine.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnRemoveLineActionPerformed(evt);
            }
        });
        pnlCartActions.add(btnRemoveLine);

        lblCartHint.setFont(new java.awt.Font("Segoe UI", 0, 11)); // NOI18N
        lblCartHint.setForeground(new java.awt.Color(127, 140, 141));
        lblCartHint.setText("Bấm đúp vào ô Ghi chú để ghi chú từng món");
        pnlCartActions.add(lblCartHint);

        pnlCart.add(pnlCartActions, java.awt.BorderLayout.NORTH);

        tblCart.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        tblCart.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {

            },
            new String [] {
                "STT", "Món", "SL", "Thành tiền", "Ghi chú"
            }
        ) {
            boolean[] canEdit = new boolean [] {
                false, false, false, false, true
            };

            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return canEdit [columnIndex];
            }
        });
        tblCart.setRowHeight(28);
        scrollCart.setViewportView(tblCart);

        pnlCart.add(scrollCart, java.awt.BorderLayout.CENTER);

        pnlCartBottom.setOpaque(false);
        pnlCartBottom.setLayout(new java.awt.GridBagLayout());

        lblOrderNote.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        lblOrderNote.setForeground(new java.awt.Color(44, 62, 80));
        lblOrderNote.setText("Ghi chú chung cho bếp");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(2, 0, 2, 0);
        pnlCartBottom.add(lblOrderNote, gridBagConstraints);

        txtOrderNote.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        txtOrderNote.setToolTipText("Ví dụ: dị ứng tôm, mang nước chấm trước, ít cay...");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 6, 0);
        pnlCartBottom.add(txtOrderNote, gridBagConstraints);

        lblCartTotal.setFont(new java.awt.Font("Segoe UI", 1, 17)); // NOI18N
        lblCartTotal.setForeground(new java.awt.Color(169, 29, 34));
        lblCartTotal.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        lblCartTotal.setText("Tổng giỏ hàng: 0 đ");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 2;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.EAST;
        gridBagConstraints.insets = new java.awt.Insets(2, 0, 6, 0);
        pnlCartBottom.add(lblCartTotal, gridBagConstraints);

        pnlCartButtons.setOpaque(false);
        pnlCartButtons.setLayout(new java.awt.GridLayout(1, 2, 8, 0));

        btnClearCart.setBackground(new java.awt.Color(236, 240, 241));
        btnClearCart.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        btnClearCart.setForeground(new java.awt.Color(44, 62, 80));
        btnClearCart.setText("Xóa giỏ");
        btnClearCart.setFocusable(false);
        btnClearCart.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnClearCartActionPerformed(evt);
            }
        });
        pnlCartButtons.add(btnClearCart);

        btnSendOrder.setBackground(new java.awt.Color(39, 174, 96));
        btnSendOrder.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        btnSendOrder.setForeground(new java.awt.Color(255, 255, 255));
        btnSendOrder.setText("GỬI ĐƠN XUỐNG BẾP");
        btnSendOrder.setFocusable(false);
        btnSendOrder.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSendOrderActionPerformed(evt);
            }
        });
        pnlCartButtons.add(btnSendOrder);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 3;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        pnlCartBottom.add(pnlCartButtons, gridBagConstraints);

        pnlCart.add(pnlCartBottom, java.awt.BorderLayout.SOUTH);

        tabRight.addTab("Giỏ hàng", pnlCart);

        pnlMyOrders.setBackground(new java.awt.Color(255, 255, 255));
        pnlMyOrders.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10));
        pnlMyOrders.setLayout(new java.awt.BorderLayout(0, 8));

        tblMyOrders.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        tblMyOrders.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {

            },
            new String [] {
                "STT", "Giờ", "Món", "Tiền", "Trạng thái"
            }
        ) {
            boolean[] canEdit = new boolean [] {
                false, false, false, false, false
            };

            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return canEdit [columnIndex];
            }
        });
        tblMyOrders.setRowHeight(28);
        scrollMyOrders.setViewportView(tblMyOrders);

        pnlMyOrders.add(scrollMyOrders, java.awt.BorderLayout.CENTER);

        pnlMyOrdersBottom.setOpaque(false);
        pnlMyOrdersBottom.setLayout(new java.awt.BorderLayout(10, 0));

        lblMyOrdersTotal.setFont(new java.awt.Font("Segoe UI", 1, 17)); // NOI18N
        lblMyOrdersTotal.setForeground(new java.awt.Color(169, 29, 34));
        lblMyOrdersTotal.setText("Tạm tính: 0 đ");
        pnlMyOrdersBottom.add(lblMyOrdersTotal, java.awt.BorderLayout.WEST);

        lblMyOrdersHint.setFont(new java.awt.Font("Segoe UI", 0, 11)); // NOI18N
        lblMyOrdersHint.setForeground(new java.awt.Color(127, 140, 141));
        lblMyOrdersHint.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        lblMyOrdersHint.setText("Trạng thái món được bếp cập nhật trực tiếp");
        pnlMyOrdersBottom.add(lblMyOrdersHint, java.awt.BorderLayout.CENTER);

        pnlMyOrders.add(pnlMyOrdersBottom, java.awt.BorderLayout.SOUTH);

        tabRight.addTab("Đơn của bàn", pnlMyOrders);

        pnlChat.setBackground(new java.awt.Color(255, 255, 255));
        pnlChat.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10));
        pnlChat.setLayout(new java.awt.BorderLayout(0, 8));

        lblChatHint.setFont(new java.awt.Font("Segoe UI", 0, 11)); // NOI18N
        lblChatHint.setForeground(new java.awt.Color(127, 140, 141));
        lblChatHint.setText("Cần hỗ trợ? Nhắn nhân viên tại đây. Tin nhắn chỉ hiện trong lượt dùng bữa này.");
        pnlChat.add(lblChatHint, java.awt.BorderLayout.NORTH);

        pnlChatHost.setBackground(new java.awt.Color(255, 255, 255));
        pnlChatHost.setLayout(new java.awt.BorderLayout());

        pnlChat.add(pnlChatHost, java.awt.BorderLayout.CENTER);

        pnlChatInput.setOpaque(false);
        pnlChatInput.setLayout(new java.awt.BorderLayout(8, 0));

        txtChatInput.setFont(new java.awt.Font("Segoe UI", 0, 14)); // NOI18N
        txtChatInput.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                txtChatInputActionPerformed(evt);
            }
        });
        pnlChatInput.add(txtChatInput, java.awt.BorderLayout.CENTER);

        btnSendChat.setBackground(new java.awt.Color(169, 29, 34));
        btnSendChat.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
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

        tabRight.addTab("Nhắn nhân viên", pnlChat);

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

        lblClock.setFont(new java.awt.Font("Segoe UI", 0, 12)); // NOI18N
        lblClock.setForeground(new java.awt.Color(127, 140, 141));
        lblClock.setText("--:--");
        pnlStatusBar.add(lblClock, java.awt.BorderLayout.EAST);

        getContentPane().add(pnlStatusBar, java.awt.BorderLayout.SOUTH);

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void btnCallStaffActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnCallStaffActionPerformed
        showServiceMenu();
    }//GEN-LAST:event_btnCallStaffActionPerformed

    private void btnPayBillActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnPayBillActionPerformed
        openPayment();
    }//GEN-LAST:event_btnPayBillActionPerformed

    private void btnMinusActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnMinusActionPerformed
        changeSelectedQuantity(-1);
    }//GEN-LAST:event_btnMinusActionPerformed

    private void btnPlusActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnPlusActionPerformed
        changeSelectedQuantity(1);
    }//GEN-LAST:event_btnPlusActionPerformed

    private void btnRemoveLineActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnRemoveLineActionPerformed
        int row = tblCart.getSelectedRow();
        if (row >= 0) setQuantity(row, 0);
    }//GEN-LAST:event_btnRemoveLineActionPerformed

    private void btnClearCartActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnClearCartActionPerformed
        clearCart();
    }//GEN-LAST:event_btnClearCartActionPerformed

    private void btnSendOrderActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSendOrderActionPerformed
        sendOrder();
    }//GEN-LAST:event_btnSendOrderActionPerformed

    private void txtChatInputActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_txtChatInputActionPerformed
        sendChat();
    }//GEN-LAST:event_txtChatInputActionPerformed

    private void btnSendChatActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSendChatActionPerformed
        sendChat();
    }//GEN-LAST:event_btnSendChatActionPerformed

    /**
     * Chạy riêng chương trình Tablet: lần đầu chọn bàn, các lần sau tự vào bàn đã chọn
     */
    public static void main(String args[]) {
        com.haidilao.pos.HaidilaoNetworkPOS.applyLookAndFeel();
        java.awt.EventQueue.invokeLater(() -> launch(args.length > 0 ? args[0] : null));
    }

    /** Mở Tablet cho bàn tableId (null = bàn đã lưu, chưa có thì hỏi) */
    public static void launch(String tableId) {
        String table = tableId;
        if (table == null || table.isEmpty()) {
            table = com.haidilao.pos.client.LocalConfig.get(com.haidilao.pos.client.LocalConfig.TABLET_TABLE, "");
        }
        if (table.isEmpty()) {
            table = new TabletSetupDialog(null).showDialog();
            if (table == null) return;
        }
        TabletClientFrame frame = new TabletClientFrame(table, null);
        frame.setVisible(true);
        frame.start();
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JPanel pnlHeader;
    private javax.swing.JPanel pnlHeaderLeft;
    private javax.swing.JLabel lblTableName;
    private javax.swing.JLabel lblGreeting;
    private javax.swing.JPanel pnlHeaderRight;
    private javax.swing.JLabel lblConnection;
    private javax.swing.JButton btnCallStaff;
    private javax.swing.JButton btnPayBill;
    private javax.swing.JSplitPane splitMain;
    private javax.swing.JPanel pnlMenu;
    private javax.swing.JLabel lblMenuHint;
    private javax.swing.JTabbedPane tabMenu;
    private javax.swing.JScrollPane scrollCombo;
    private javax.swing.JPanel pnlCombo;
    private javax.swing.JPanel pnlRight;
    private javax.swing.JTabbedPane tabRight;
    private javax.swing.JPanel pnlCart;
    private javax.swing.JPanel pnlCartActions;
    private javax.swing.JButton btnMinus;
    private javax.swing.JButton btnPlus;
    private javax.swing.JButton btnRemoveLine;
    private javax.swing.JLabel lblCartHint;
    private javax.swing.JScrollPane scrollCart;
    private javax.swing.JTable tblCart;
    private javax.swing.JPanel pnlCartBottom;
    private javax.swing.JLabel lblOrderNote;
    private javax.swing.JTextField txtOrderNote;
    private javax.swing.JLabel lblCartTotal;
    private javax.swing.JPanel pnlCartButtons;
    private javax.swing.JButton btnClearCart;
    private javax.swing.JButton btnSendOrder;
    private javax.swing.JPanel pnlMyOrders;
    private javax.swing.JScrollPane scrollMyOrders;
    private javax.swing.JTable tblMyOrders;
    private javax.swing.JPanel pnlMyOrdersBottom;
    private javax.swing.JLabel lblMyOrdersTotal;
    private javax.swing.JLabel lblMyOrdersHint;
    private javax.swing.JPanel pnlChat;
    private javax.swing.JLabel lblChatHint;
    private javax.swing.JPanel pnlChatHost;
    private javax.swing.JPanel pnlChatInput;
    private javax.swing.JTextField txtChatInput;
    private javax.swing.JButton btnSendChat;
    private javax.swing.JPanel pnlStatusBar;
    private javax.swing.JLabel lblToast;
    private javax.swing.JLabel lblClock;
    // End of variables declaration//GEN-END:variables
}
