/*
 * TableDetailDialog.java
 */
package com.haidilao.pos;

import com.haidilao.pos.client.MessageListener;
import com.haidilao.pos.model.DiningTable;
import com.haidilao.pos.model.Order;
import com.haidilao.pos.protocol.NetworkPacket;
import com.haidilao.pos.ui.ChatPanel;
import com.haidilao.pos.ui.Theme;
import javax.swing.JOptionPane;
import javax.swing.table.DefaultTableModel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Chi tiết một bàn (Lễ tân): thông tin khách, nhân viên phục vụ, ghi chú; các đơn của bàn; nhắn tin riêng với bàn;
 * mở bàn, thanh toán, chuyển bàn, đánh dấu đã dọn.
 */
public class TableDetailDialog extends javax.swing.JDialog implements MessageListener {

    private final ReceptionFrame owner;
    private final String tableId;
    private final List<Integer> waiterIds = new ArrayList<>();
    private ChatPanel chat;
    private DefaultTableModel ordersModel;
    private int sessionId = 0;
    private int capacity = 99;

    private void setGuests(int value) {
        int v = Math.max(1, Math.min(capacity, value));
        spnGuestCount.setModel(new javax.swing.SpinnerNumberModel(v, 1, capacity, 1));
    }
    private boolean loading = false;

    public TableDetailDialog(ReceptionFrame owner, String tableId) {
        super(owner, false);
        this.owner = owner;
        this.tableId = tableId;
        initComponents();
        ordersModel = (DefaultTableModel) tblTableOrders.getModel();
        Theme.styleTable(tblTableOrders, 5);
        int[] w = {45, 50, 50, 240, 90, 100, 120};
        for (int i = 0; i < w.length; i++) tblTableOrders.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        chat = ChatPanel.installInto(pnlChatHost);
        fillWaiters(0);
        applyInfo(owner.getTableInfo(tableId));
        owner.addListener(this);
        setSize(1080, 680);
        setLocationRelativeTo(owner);
        owner.send(NetworkPacket.buildGetTableDetail(tableId));
    }

    public String getTableId() {
        return tableId;
    }

    @Override
    public void dispose() {
        owner.removeListener(this);
        super.dispose();
    }

    private void fillWaiters(int selectedId) {
        cboWaiter.removeAllItems();
        waiterIds.clear();
        cboWaiter.addItem("(Chưa gán)");
        waiterIds.add(0);
        for (Map.Entry<Integer, String> w : owner.getWaiters().entrySet()) {
            cboWaiter.addItem(owner.waiterLabel(w.getKey()));
            waiterIds.add(w.getKey());
        }
        int i = waiterIds.indexOf(selectedId);
        cboWaiter.setSelectedIndex(Math.max(0, i));
    }

    /** Tiêu đề + trạng thái nút theo TABLE_INFO mới nhất */
    private void applyInfo(String[] info) {
        if (info == null) return;
        lblTableTitle.setText(info[2].toUpperCase());
        lblTableSub.setText(info[3] + " ghế · " + info[4] + " · Tablet " + ("1".equals(info[10]) ? "đang kết nối" : "offline")
                + (info.length > 12 && !info[12].isEmpty() ? " · Đặt trước: " + info[12] : ""));
        String status = info[5] + (info.length > 13 && !info[13].isEmpty() ? " · khách yêu cầu thanh toán (" + info[13] + ")" : "");
        lblTableStatus.setText(status);
        boolean serving = !"0".equals(info[6]);
        btnCheckout.setEnabled(serving);
        btnMoveTable.setEnabled(serving);
        btnCancelTable.setEnabled(serving);
        btnCleaned.setEnabled(!serving && !DiningTable.STATUS_EMPTY.equals(info[5]));
        btnSaveInfo.setText(serving ? "Lưu thông tin khách" : "Mở bàn cho khách");
        setTitle("Chi tiết " + info[2]);
        // Số khách không được vượt số ghế của bàn
        capacity = Math.max(1, NetworkPacket.parseInt(info[3], 1));
        setGuests((Integer) spnGuestCount.getValue());
        lblGuestCount.setToolTipText("Tối đa " + capacity + " khách (số ghế của bàn)");
    }

    private void changeOrder(String status) {
        int row = tblTableOrders.getSelectedRow();
        if (row < 0) {
            owner.toast("Chọn một đơn trong bảng trước.");
            return;
        }
        String current = String.valueOf(ordersModel.getValueAt(row, 5));
        if (Order.STATUS_SERVED.equals(current) || Order.STATUS_CANCELLED.equals(current)) {
            owner.toast("Đơn này đã " + current.toLowerCase() + ".");
            return;
        }
        if (Order.STATUS_CANCELLED.equals(status) && JOptionPane.showConfirmDialog(this, "Hủy đơn " + ordersModel.getValueAt(row, 1) + "?",
                "Hủy đơn", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        owner.send(NetworkPacket.buildOrderStatusUpdate(Integer.parseInt(String.valueOf(ordersModel.getValueAt(row, 0))), tableId, status));
    }

    private void sendChat() {
        String msg = txtMessage.getText().trim();
        if (msg.isEmpty()) return;
        owner.send(NetworkPacket.buildChatToTable(tableId, msg));
        chat.addMessage("Bạn (Lễ tân)", msg, new SimpleDateFormat("HH:mm").format(new Date()), true);
        txtMessage.setText("");
    }

    @Override
    public void onServerMessage(String[] f, String raw) {
        String time = new SimpleDateFormat("HH:mm").format(new Date());
        switch (f[0]) {
            case "TABLE_SESSION":
                // TABLE_SESSION|BÀN|MÃ_PHIÊN|TÊN|SĐT|SỐ_KHÁCH|MÃ_PV|TÊN_PV|GHI_CHÚ|TRẠNG_THÁI_BÀN|GIỜ_VÀO|TẠM_TÍNH|TIỀN_CỌC|YÊU_CẦU_TT
                if (!tableId.equals(f[1])) return;
                loading = true;
                sessionId = NetworkPacket.parseInt(f[2], 0);
                txtCustomerName.setText(NetworkPacket.dec(f[3]));
                txtCustomerPhone.setText(NetworkPacket.dec(f[4]));
                setGuests(NetworkPacket.parseInt(f[5], 1));
                fillWaiters(NetworkPacket.parseInt(f[6], 0));
                txtSessionNote.setText(NetworkPacket.dec(f[8]));
                lblOpenedValue.setText(f[10].isEmpty() ? "-" : f[10]);
                lblSubtotalValue.setText(Theme.money(NetworkPacket.parseInt(f[11], 0)));
                lblDepositValue.setText(Theme.money(f.length > 12 ? NetworkPacket.parseInt(f[12], 0) : 0));
                ordersModel.setRowCount(0);
                chat.clear();
                break;
            case "TABLE_ORDER":
                // TABLE_ORDER|MÃ_ĐƠN|STT|GIỜ|TÓM_TẮT|TỔNG|TRẠNG_THÁI|GHI_CHÚ
                if (loading) {
                    ordersModel.insertRow(0, new Object[]{f[1], Order.formatOrderNo(NetworkPacket.parseInt(f[2], 0)), f[3], NetworkPacket.dec(f[4]),
                            Theme.money(NetworkPacket.parseInt(f[5], 0)), NetworkPacket.dec(f[6]), NetworkPacket.dec(f[7])});
                }
                break;
            case "TABLE_CHAT":
                // TABLE_CHAT|GIỜ|NGƯỜI_GỬI|NGƯỜI_NHẬN|LOẠI|NỘI_DUNG
                if (loading) {
                    String content = NetworkPacket.dec(f[5]);
                    if ("RING".equals(f[4])) chat.addNotice(f[1] + " · khách bấm chuông: " + content);
                    else {
                        String name = f.length > 6 ? NetworkPacket.dec(f[6]) : "";
                        boolean in = f[2].equals(tableId);
                        chat.addMessage(name.isEmpty() ? (in ? "Khách" : "Nhân viên") : name, content, f[1], !in);
                    }
                }
                break;
            case "TABLE_DETAIL_END":
                if (tableId.equals(f[1])) loading = false;
                break;
            case "TABLE_INFO":
                if (tableId.equals(f[1])) {
                    String[] info = owner.getTableInfo(tableId);
                    applyInfo(info);
                    if (info != null && NetworkPacket.parseInt(info[6], 0) != sessionId) owner.send(NetworkPacket.buildGetTableDetail(tableId));
                }
                break;
            case "NEW_ORDER":
                if (f.length > 3 && tableId.equals(f[3])) owner.send(NetworkPacket.buildGetTableDetail(tableId));
                break;
            case "ORDER_UPDATED":
                for (int r = 0; r < ordersModel.getRowCount(); r++) {
                    if (f[1].equals(String.valueOf(ordersModel.getValueAt(r, 0)))) {
                        ordersModel.setValueAt(f[2], r, 5);
                        if (Order.STATUS_CANCELLED.equals(f[2])) owner.send(NetworkPacket.buildGetTableDetail(tableId));
                    }
                }
                break;
            case "CHAT_EVENT":
                // CHAT_EVENT|BÀN|IN/OUT|TÊN_NGƯỜI_GỬI|GIỜ|NỘI_DUNG
                if (f.length >= 6 && tableId.equals(f[1])) {
                    chat.addMessage(NetworkPacket.dec(f[3]), NetworkPacket.dec(f[5]), f[4], "OUT".equals(f[2]));
                }
                break;
            case "RING":
                if (tableId.equals(f[1])) chat.addNotice(time + " · khách bấm chuông: " + (f.length > 2 ? f[2] : ""));
                break;
            case "SESSION_SAVED":
                if (tableId.equals(f[1])) {
                    owner.toast("Đã lưu thông tin khách của " + owner.tableName(tableId) + ".");
                    owner.send(NetworkPacket.buildGetTableDetail(tableId));
                }
                break;
            case "SESSION_SAVE_FAIL":
                if (tableId.equals(f[1])) JOptionPane.showMessageDialog(this, NetworkPacket.dec(f[2]), "Chưa lưu được", JOptionPane.WARNING_MESSAGE);
                break;
            case "SESSION_CANCELLED":
                if (tableId.equals(f[1])) owner.send(NetworkPacket.buildGetTableDetail(tableId));
                break;
            case "TABLE_MOVED":
                if (tableId.equals(f[1])) dispose();
                break;
            case "BILL":
                if (tableId.equals(f[1])) owner.send(NetworkPacket.buildGetTableDetail(tableId));
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
        java.awt.GridBagConstraints gridBagConstraints;

        pnlHeader = new javax.swing.JPanel();
        pnlHeaderLeft = new javax.swing.JPanel();
        lblTableTitle = new javax.swing.JLabel();
        lblTableSub = new javax.swing.JLabel();
        lblTableStatus = new javax.swing.JLabel();
        splitDetail = new javax.swing.JSplitPane();
        pnlLeft = new javax.swing.JPanel();
        pnlCustomer = new javax.swing.JPanel();
        lblCustomerName = new javax.swing.JLabel();
        txtCustomerName = new javax.swing.JTextField();
        lblCustomerPhone = new javax.swing.JLabel();
        txtCustomerPhone = new javax.swing.JTextField();
        lblGuestCount = new javax.swing.JLabel();
        spnGuestCount = new javax.swing.JSpinner();
        lblWaiter = new javax.swing.JLabel();
        cboWaiter = new javax.swing.JComboBox<>();
        lblSessionNote = new javax.swing.JLabel();
        scrollSessionNote = new javax.swing.JScrollPane();
        txtSessionNote = new javax.swing.JTextArea();
        lblOpened = new javax.swing.JLabel();
        lblOpenedValue = new javax.swing.JLabel();
        lblDeposit = new javax.swing.JLabel();
        lblDepositValue = new javax.swing.JLabel();
        lblSubtotal = new javax.swing.JLabel();
        lblSubtotalValue = new javax.swing.JLabel();
        btnSaveInfo = new javax.swing.JButton();
        pnlActions = new javax.swing.JPanel();
        btnCheckout = new javax.swing.JButton();
        btnMoveTable = new javax.swing.JButton();
        btnCancelTable = new javax.swing.JButton();
        btnCleaned = new javax.swing.JButton();
        splitRight = new javax.swing.JSplitPane();
        pnlTableOrders = new javax.swing.JPanel();
        scrollTableOrders = new javax.swing.JScrollPane();
        tblTableOrders = new javax.swing.JTable();
        pnlOrderButtons = new javax.swing.JPanel();
        btnOrderCancel = new javax.swing.JButton();
        btnOrderServed = new javax.swing.JButton();
        pnlTableChat = new javax.swing.JPanel();
        pnlChatHost = new javax.swing.JPanel();
        pnlChatInput = new javax.swing.JPanel();
        txtMessage = new javax.swing.JTextField();
        btnSend = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        setTitle("Chi tiết bàn");

        pnlHeader.setBackground(new java.awt.Color(169, 29, 34));
        pnlHeader.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 16, 10, 16));
        pnlHeader.setLayout(new java.awt.BorderLayout(12, 0));

        pnlHeaderLeft.setOpaque(false);
        pnlHeaderLeft.setLayout(new java.awt.GridLayout(2, 1));

        lblTableTitle.setFont(new java.awt.Font("Segoe UI", 1, 18)); // NOI18N
        lblTableTitle.setForeground(new java.awt.Color(255, 255, 255));
        lblTableTitle.setText("BÀN");
        pnlHeaderLeft.add(lblTableTitle);

        lblTableSub.setForeground(new java.awt.Color(245, 214, 214));
        lblTableSub.setText("-");
        pnlHeaderLeft.add(lblTableSub);

        pnlHeader.add(pnlHeaderLeft, java.awt.BorderLayout.WEST);

        lblTableStatus.setFont(new java.awt.Font("Segoe UI", 1, 15)); // NOI18N
        lblTableStatus.setForeground(new java.awt.Color(241, 196, 15));
        lblTableStatus.setText("-");
        pnlHeader.add(lblTableStatus, java.awt.BorderLayout.EAST);

        getContentPane().add(pnlHeader, java.awt.BorderLayout.NORTH);

        splitDetail.setDividerLocation(380);
        splitDetail.setResizeWeight(0.38);

        pnlLeft.setBackground(new java.awt.Color(244, 245, 247));
        pnlLeft.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 5));
        pnlLeft.setLayout(new java.awt.BorderLayout(0, 10));

        pnlCustomer.setBackground(new java.awt.Color(255, 255, 255));
        pnlCustomer.setBorder(javax.swing.BorderFactory.createTitledBorder(null, "Thông tin khách", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Segoe UI", 1, 12), new java.awt.Color(44, 62, 80))); // NOI18N
        pnlCustomer.setLayout(new java.awt.GridBagLayout());

        lblCustomerName.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblCustomerName.setForeground(new java.awt.Color(44, 62, 80));
        lblCustomerName.setText("Tên khách *");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(5, 4, 5, 8);
        pnlCustomer.add(lblCustomerName, gridBagConstraints);

        txtCustomerName.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(5, 0, 5, 4);
        pnlCustomer.add(txtCustomerName, gridBagConstraints);

        lblCustomerPhone.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblCustomerPhone.setForeground(new java.awt.Color(44, 62, 80));
        lblCustomerPhone.setText("Số điện thoại");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(5, 4, 5, 8);
        pnlCustomer.add(lblCustomerPhone, gridBagConstraints);

        txtCustomerPhone.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(5, 0, 5, 4);
        pnlCustomer.add(txtCustomerPhone, gridBagConstraints);

        lblGuestCount.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblGuestCount.setForeground(new java.awt.Color(44, 62, 80));
        lblGuestCount.setText("Số khách *");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 2;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(5, 4, 5, 8);
        pnlCustomer.add(lblGuestCount, gridBagConstraints);

        spnGuestCount.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        spnGuestCount.setModel(new javax.swing.SpinnerNumberModel(1, 1, 99, 1));
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(5, 0, 5, 4);
        pnlCustomer.add(spnGuestCount, gridBagConstraints);

        lblWaiter.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblWaiter.setForeground(new java.awt.Color(44, 62, 80));
        lblWaiter.setText("Phục vụ");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 3;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(5, 4, 5, 8);
        pnlCustomer.add(lblWaiter, gridBagConstraints);

        cboWaiter.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        cboWaiter.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "(Chưa gán)" }));
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 3;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(5, 0, 5, 4);
        pnlCustomer.add(cboWaiter, gridBagConstraints);

        lblSessionNote.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblSessionNote.setForeground(new java.awt.Color(44, 62, 80));
        lblSessionNote.setText("Ghi chú");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 4;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(5, 4, 5, 8);
        pnlCustomer.add(lblSessionNote, gridBagConstraints);

        txtSessionNote.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        txtSessionNote.setColumns(20);
        txtSessionNote.setLineWrap(true);
        txtSessionNote.setRows(3);
        txtSessionNote.setWrapStyleWord(true);
        scrollSessionNote.setViewportView(txtSessionNote);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 4;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(5, 0, 5, 4);
        pnlCustomer.add(scrollSessionNote, gridBagConstraints);

        lblOpened.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        lblOpened.setForeground(new java.awt.Color(127, 140, 141));
        lblOpened.setText("Giờ vào");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 5;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(5, 4, 5, 8);
        pnlCustomer.add(lblOpened, gridBagConstraints);

        lblOpenedValue.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblOpenedValue.setForeground(new java.awt.Color(44, 62, 80));
        lblOpenedValue.setText("-");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 5;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(5, 0, 5, 4);
        pnlCustomer.add(lblOpenedValue, gridBagConstraints);

        lblDeposit.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        lblDeposit.setForeground(new java.awt.Color(127, 140, 141));
        lblDeposit.setText("Đã đặt cọc");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 6;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(5, 4, 5, 8);
        pnlCustomer.add(lblDeposit, gridBagConstraints);

        lblDepositValue.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblDepositValue.setForeground(new java.awt.Color(44, 62, 80));
        lblDepositValue.setText("0 đ");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 6;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(5, 0, 5, 4);
        pnlCustomer.add(lblDepositValue, gridBagConstraints);

        lblSubtotal.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        lblSubtotal.setForeground(new java.awt.Color(127, 140, 141));
        lblSubtotal.setText("Tạm tính");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 7;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(5, 4, 5, 8);
        pnlCustomer.add(lblSubtotal, gridBagConstraints);

        lblSubtotalValue.setFont(new java.awt.Font("Segoe UI", 1, 16)); // NOI18N
        lblSubtotalValue.setForeground(new java.awt.Color(169, 29, 34));
        lblSubtotalValue.setText("0 đ");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 7;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(5, 0, 5, 4);
        pnlCustomer.add(lblSubtotalValue, gridBagConstraints);

        btnSaveInfo.setBackground(new java.awt.Color(169, 29, 34));
        btnSaveInfo.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnSaveInfo.setForeground(new java.awt.Color(255, 255, 255));
        btnSaveInfo.setText("Lưu thông tin khách");
        btnSaveInfo.setFocusable(false);
        btnSaveInfo.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSaveInfoActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 8;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(8, 4, 4, 4);
        pnlCustomer.add(btnSaveInfo, gridBagConstraints);

        pnlLeft.add(pnlCustomer, java.awt.BorderLayout.CENTER);

        pnlActions.setOpaque(false);
        pnlActions.setLayout(new java.awt.GridLayout(4, 1, 0, 6));

        btnCheckout.setBackground(new java.awt.Color(39, 174, 96));
        btnCheckout.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        btnCheckout.setForeground(new java.awt.Color(255, 255, 255));
        btnCheckout.setText("Thanh toán");
        btnCheckout.setFocusable(false);
        btnCheckout.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnCheckoutActionPerformed(evt);
            }
        });
        pnlActions.add(btnCheckout);

        btnMoveTable.setBackground(new java.awt.Color(41, 128, 185));
        btnMoveTable.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnMoveTable.setForeground(new java.awt.Color(255, 255, 255));
        btnMoveTable.setText("Chuyển sang bàn khác");
        btnMoveTable.setFocusable(false);
        btnMoveTable.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnMoveTableActionPerformed(evt);
            }
        });
        pnlActions.add(btnMoveTable);

        btnCancelTable.setBackground(new java.awt.Color(236, 240, 241));
        btnCancelTable.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnCancelTable.setForeground(new java.awt.Color(44, 62, 80));
        btnCancelTable.setText("Hủy bàn (khách về, chưa gọi món)");
        btnCancelTable.setFocusable(false);
        btnCancelTable.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnCancelTableActionPerformed(evt);
            }
        });
        pnlActions.add(btnCancelTable);

        btnCleaned.setBackground(new java.awt.Color(149, 165, 166));
        btnCleaned.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnCleaned.setForeground(new java.awt.Color(255, 255, 255));
        btnCleaned.setText("Đã dọn bàn xong");
        btnCleaned.setFocusable(false);
        btnCleaned.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnCleanedActionPerformed(evt);
            }
        });
        pnlActions.add(btnCleaned);

        pnlLeft.add(pnlActions, java.awt.BorderLayout.SOUTH);

        splitDetail.setLeftComponent(pnlLeft);

        splitRight.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 5, 10, 10));
        splitRight.setOrientation(javax.swing.JSplitPane.VERTICAL_SPLIT);
        splitRight.setResizeWeight(0.55);

        pnlTableOrders.setBackground(new java.awt.Color(255, 255, 255));
        pnlTableOrders.setBorder(javax.swing.BorderFactory.createTitledBorder(null, "Các đơn của bàn", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Segoe UI", 1, 12), new java.awt.Color(44, 62, 80))); // NOI18N
        pnlTableOrders.setLayout(new java.awt.BorderLayout(0, 6));

        tblTableOrders.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        tblTableOrders.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {

            },
            new String [] {
                "Mã", "STT", "Giờ", "Món", "Tổng tiền", "Trạng thái", "Ghi chú"
            }
        ) {
            boolean[] canEdit = new boolean [] {
                false, false, false, false, false, false, false
            };

            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return canEdit [columnIndex];
            }
        });
        tblTableOrders.setRowHeight(28);
        scrollTableOrders.setViewportView(tblTableOrders);

        pnlTableOrders.add(scrollTableOrders, java.awt.BorderLayout.CENTER);

        pnlOrderButtons.setOpaque(false);
        pnlOrderButtons.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));

        btnOrderCancel.setBackground(new java.awt.Color(231, 76, 60));
        btnOrderCancel.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnOrderCancel.setForeground(new java.awt.Color(255, 255, 255));
        btnOrderCancel.setText("Hủy đơn");
        btnOrderCancel.setFocusable(false);
        btnOrderCancel.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnOrderCancelActionPerformed(evt);
            }
        });
        pnlOrderButtons.add(btnOrderCancel);

        btnOrderServed.setBackground(new java.awt.Color(39, 174, 96));
        btnOrderServed.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnOrderServed.setForeground(new java.awt.Color(255, 255, 255));
        btnOrderServed.setText("Đã mang món ra");
        btnOrderServed.setFocusable(false);
        btnOrderServed.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnOrderServedActionPerformed(evt);
            }
        });
        pnlOrderButtons.add(btnOrderServed);

        pnlTableOrders.add(pnlOrderButtons, java.awt.BorderLayout.SOUTH);

        splitRight.setTopComponent(pnlTableOrders);

        pnlTableChat.setBackground(new java.awt.Color(255, 255, 255));
        pnlTableChat.setBorder(javax.swing.BorderFactory.createTitledBorder(null, "Nhắn tin với bàn", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Segoe UI", 1, 12), new java.awt.Color(44, 62, 80))); // NOI18N
        pnlTableChat.setLayout(new java.awt.BorderLayout(0, 6));

        pnlChatHost.setBackground(new java.awt.Color(255, 255, 255));
        pnlChatHost.setLayout(new java.awt.BorderLayout());
        pnlTableChat.add(pnlChatHost, java.awt.BorderLayout.CENTER);

        pnlChatInput.setOpaque(false);
        pnlChatInput.setLayout(new java.awt.BorderLayout(6, 0));

        txtMessage.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        txtMessage.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                txtMessageActionPerformed(evt);
            }
        });
        pnlChatInput.add(txtMessage, java.awt.BorderLayout.CENTER);

        btnSend.setBackground(new java.awt.Color(169, 29, 34));
        btnSend.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnSend.setForeground(new java.awt.Color(255, 255, 255));
        btnSend.setText("Gửi");
        btnSend.setFocusable(false);
        btnSend.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSendActionPerformed(evt);
            }
        });
        pnlChatInput.add(btnSend, java.awt.BorderLayout.EAST);

        pnlTableChat.add(pnlChatInput, java.awt.BorderLayout.SOUTH);

        splitRight.setBottomComponent(pnlTableChat);

        splitDetail.setRightComponent(splitRight);

        getContentPane().add(splitDetail, java.awt.BorderLayout.CENTER);

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void btnSaveInfoActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSaveInfoActionPerformed
        int guests = (Integer) spnGuestCount.getValue();
        if (txtCustomerName.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập tên khách để biết ai đang dùng bàn.", "Thiếu thông tin", JOptionPane.WARNING_MESSAGE);
            txtCustomerName.requestFocusInWindow();
            return;
        }
        String[] info = owner.getTableInfo(tableId);
        if (info != null && "0".equals(info[6]) && info.length > 12 && !info[12].isEmpty()
                && JOptionPane.showConfirmDialog(this, owner.tableName(tableId) + " có khách đặt trước: " + info[12] + ".\nVẫn mở bàn cho khách vãng lai?",
                "Bàn đã có người đặt", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        int idx = Math.max(0, cboWaiter.getSelectedIndex());
        owner.send(NetworkPacket.buildSaveSession(tableId, txtCustomerName.getText().trim(), txtCustomerPhone.getText().trim(),
                guests, waiterIds.get(idx), txtSessionNote.getText().trim()));
    }//GEN-LAST:event_btnSaveInfoActionPerformed

    private void btnCheckoutActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnCheckoutActionPerformed
        owner.openCheckout(tableId);
    }//GEN-LAST:event_btnCheckoutActionPerformed

    private void btnMoveTableActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnMoveTableActionPerformed
        owner.moveTable(tableId);
    }//GEN-LAST:event_btnMoveTableActionPerformed

    private void btnCancelTableActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnCancelTableActionPerformed
        owner.cancelTable(tableId);
    }//GEN-LAST:event_btnCancelTableActionPerformed

    private void btnCleanedActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnCleanedActionPerformed
        owner.send(NetworkPacket.buildSetTableStatus(tableId, DiningTable.STATUS_EMPTY));
    }//GEN-LAST:event_btnCleanedActionPerformed

    private void btnOrderCancelActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnOrderCancelActionPerformed
        changeOrder(Order.STATUS_CANCELLED);
    }//GEN-LAST:event_btnOrderCancelActionPerformed

    private void btnOrderServedActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnOrderServedActionPerformed
        changeOrder(Order.STATUS_SERVED);
    }//GEN-LAST:event_btnOrderServedActionPerformed

    private void txtMessageActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_txtMessageActionPerformed
        sendChat();
    }//GEN-LAST:event_txtMessageActionPerformed

    private void btnSendActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSendActionPerformed
        sendChat();
    }//GEN-LAST:event_btnSendActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnCancelTable;
    private javax.swing.JButton btnCheckout;
    private javax.swing.JButton btnCleaned;
    private javax.swing.JButton btnMoveTable;
    private javax.swing.JButton btnOrderCancel;
    private javax.swing.JButton btnOrderServed;
    private javax.swing.JButton btnSaveInfo;
    private javax.swing.JButton btnSend;
    private javax.swing.JComboBox<String> cboWaiter;
    private javax.swing.JLabel lblCustomerName;
    private javax.swing.JLabel lblCustomerPhone;
    private javax.swing.JLabel lblDeposit;
    private javax.swing.JLabel lblDepositValue;
    private javax.swing.JLabel lblGuestCount;
    private javax.swing.JLabel lblOpened;
    private javax.swing.JLabel lblOpenedValue;
    private javax.swing.JLabel lblSessionNote;
    private javax.swing.JLabel lblSubtotal;
    private javax.swing.JLabel lblSubtotalValue;
    private javax.swing.JLabel lblTableStatus;
    private javax.swing.JLabel lblTableSub;
    private javax.swing.JLabel lblTableTitle;
    private javax.swing.JLabel lblWaiter;
    private javax.swing.JPanel pnlActions;
    private javax.swing.JPanel pnlChatHost;
    private javax.swing.JPanel pnlChatInput;
    private javax.swing.JPanel pnlCustomer;
    private javax.swing.JPanel pnlHeader;
    private javax.swing.JPanel pnlHeaderLeft;
    private javax.swing.JPanel pnlLeft;
    private javax.swing.JPanel pnlOrderButtons;
    private javax.swing.JPanel pnlTableChat;
    private javax.swing.JPanel pnlTableOrders;
    private javax.swing.JScrollPane scrollSessionNote;
    private javax.swing.JScrollPane scrollTableOrders;
    private javax.swing.JSplitPane splitDetail;
    private javax.swing.JSplitPane splitRight;
    private javax.swing.JSpinner spnGuestCount;
    private javax.swing.JTable tblTableOrders;
    private javax.swing.JTextField txtCustomerName;
    private javax.swing.JTextField txtCustomerPhone;
    private javax.swing.JTextField txtMessage;
    private javax.swing.JTextArea txtSessionNote;
    // End of variables declaration//GEN-END:variables
}
