/*
 * CheckoutDialog.java
 */
package com.haidilao.pos;

import com.haidilao.pos.client.MessageListener;
import com.haidilao.pos.protocol.NetworkPacket;
import com.haidilao.pos.server.RestaurantService;
import com.haidilao.pos.ui.Theme;
import javax.swing.ButtonGroup;
import javax.swing.JOptionPane;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;

/**
 * Lễ tân thanh toán một bàn: hóa đơn, giảm giá (số tiền hoặc %), trừ tiền cọc, tiền mặt (tính tiền thối) hoặc chuyển khoản.
 */
public class CheckoutDialog extends javax.swing.JDialog implements MessageListener {

    private static int openCount = 0;
    private final ReceptionFrame owner;
    private final String tableId;
    private final ButtonGroup methodGroup = new ButtonGroup();
    private long subtotal = 0;
    private long deposit = 0;
    private String bankInfo = "";
    private boolean loaded = false;

    public CheckoutDialog(ReceptionFrame owner, String tableId, String requestedMethod) {
        super(owner, true);
        this.owner = owner;
        this.tableId = tableId;
        initComponents();
        methodGroup.add(rdoCash);
        methodGroup.add(rdoTransfer);
        if (RestaurantService.PAY_TRANSFER.equals(requestedMethod)) rdoTransfer.setSelected(true);
        rdoCash.addActionListener(e -> recalc());
        rdoTransfer.addActionListener(e -> recalc());
        DocumentListener dl = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { recalc(); }
            public void removeUpdate(DocumentEvent e) { recalc(); }
            public void changedUpdate(DocumentEvent e) { recalc(); }
        };
        txtDiscount.getDocument().addDocumentListener(dl);
        txtCashGiven.getDocument().addDocumentListener(dl);
        Theme.styleTable(tblBill, -1);
        int[] w = {40, 200, 40, 90, 100};
        for (int i = 0; i < w.length; i++) tblBill.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        pnlPay.setPreferredSize(new java.awt.Dimension(360, 0));
        lblTitle.setText("THANH TOÁN · " + owner.tableName(tableId).toUpperCase());
        String[] info = owner.getTableInfo(tableId);
        String customer = (info != null && !info[7].isEmpty()) ? "Khách: " + info[7] + " · " + info[8] + " người" : "Khách vãng lai";
        lblSubtitle.setText(customer + (requestedMethod.isEmpty() ? "" : " · Khách yêu cầu: " + requestedMethod));
        btnConfirm.setEnabled(false);
        owner.addListener(this);
        openCount++;
        setSize(900, 560);
        setLocationRelativeTo(owner);
        owner.send(NetworkPacket.buildGetBill(tableId));
        recalc();
    }

    /** Có hộp thanh toán nào đang mở không (để cửa sổ chính biết ai xử lý bản tin BILL) */
    public static boolean isOpen() {
        return openCount > 0;
    }

    @Override
    public void dispose() {
        if (isDisplayable()) {
            openCount = Math.max(0, openCount - 1);
            owner.removeListener(this);
        }
        super.dispose();
    }

    private static long parseMoney(String s) {
        String digits = s.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return 0;
        return digits.length() > 15 ? Long.MAX_VALUE : Long.parseLong(digits);
    }

    /** Số tiền giảm (đổi % sang tiền) */
    private long discountAmount() {
        long v = parseMoney(txtDiscount.getText());
        if ("%".equals(cboDiscountType.getSelectedItem())) return subtotal * Math.min(v, 100) / 100;
        return v;
    }

    private long due() {
        return Math.max(0, subtotal - discountAmount() - deposit);
    }

    private void recalc() {
        boolean cash = rdoCash.isSelected();
        txtCashGiven.setEnabled(cash);
        lblDue.setText(Theme.money(due()));
        long given = parseMoney(txtCashGiven.getText());
        lblChange.setText(cash && given > 0 ? Theme.money(Math.max(0, given - due())) : "-");
        lblTransferInfo.setText(cash ? " " : "<html>" + bankInfo + "</html>");
        String error = " ";
        if (discountAmount() > subtotal) error = "Giảm giá không được lớn hơn tạm tính.";
        else if (cash && given > 0 && given < due()) error = "Khách đưa chưa đủ tiền.";
        lblError.setText(error);
        btnConfirm.setEnabled(loaded && subtotal > 0 && " ".equals(error));
    }

    @Override
    public void onServerMessage(String[] f, String raw) {
        switch (f[0]) {
            case "BILL_PREVIEW": {
                // BILL_PREVIEW|MÃ_PHIÊN|TẠM_TÍNH|TIỀN_CỌC|CÁC_DÒNG|NGÂN_HÀNG|SỐ_TK|CHỦ_TK|NỘI_DUNG_CK|YÊU_CẦU
                if (f.length < 10 || loaded) return;
                DefaultTableModel model = (DefaultTableModel) tblBill.getModel();
                model.setRowCount(0);
                for (String line : NetworkPacket.dec(f[4]).split("\n")) {
                    String[] c = line.split("\t");
                    if (c.length >= 4) {
                        model.addRow(new Object[]{model.getRowCount() + 1, c[0], c[1], Theme.money(Long.parseLong(c[2])), Theme.money(Long.parseLong(c[3]))});
                    }
                }
                subtotal = NetworkPacket.parseInt(f[2], 0);
                deposit = NetworkPacket.parseInt(f[3], 0);
                bankInfo = "Chuyển khoản tới <b>" + NetworkPacket.dec(f[5]) + " · " + NetworkPacket.dec(f[6]) + "</b><br>"
                        + NetworkPacket.dec(f[7]) + "<br>Nội dung: <b>" + NetworkPacket.dec(f[8]) + "</b><br>Kiểm tra tiền đã về rồi bấm xác nhận.";
                lblSubtotal.setText(Theme.money(subtotal));
                lblDeposit.setText(Theme.money(deposit));
                loaded = true;
                recalc();
                break;
            }
            case "BILL":
                if (tableId.equals(f[1])) {
                    dispose();
                    ReceptionFrame.showBill(owner, f);
                }
                break;
            case "CHECKOUT_FAIL":
                if (tableId.equals(f[1])) {
                    lblError.setText("<html>" + NetworkPacket.dec(f[2]) + "</html>");
                    btnConfirm.setEnabled(true);
                }
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
        lblTitle = new javax.swing.JLabel();
        lblSubtitle = new javax.swing.JLabel();
        pnlBody = new javax.swing.JPanel();
        scrollBill = new javax.swing.JScrollPane();
        tblBill = new javax.swing.JTable();
        pnlPay = new javax.swing.JPanel();
        lblSubtotalCaption = new javax.swing.JLabel();
        lblSubtotal = new javax.swing.JLabel();
        lblDiscountCaption = new javax.swing.JLabel();
        pnlDiscount = new javax.swing.JPanel();
        txtDiscount = new javax.swing.JTextField();
        cboDiscountType = new javax.swing.JComboBox<>();
        lblDepositCaption = new javax.swing.JLabel();
        lblDeposit = new javax.swing.JLabel();
        lblDueCaption = new javax.swing.JLabel();
        lblDue = new javax.swing.JLabel();
        lblMethodCaption = new javax.swing.JLabel();
        pnlMethods = new javax.swing.JPanel();
        rdoCash = new javax.swing.JRadioButton();
        rdoTransfer = new javax.swing.JRadioButton();
        lblCashCaption = new javax.swing.JLabel();
        txtCashGiven = new javax.swing.JTextField();
        lblChangeCaption = new javax.swing.JLabel();
        lblChange = new javax.swing.JLabel();
        lblTransferInfo = new javax.swing.JLabel();
        lblError = new javax.swing.JLabel();
        pnlButtons = new javax.swing.JPanel();
        btnCancel = new javax.swing.JButton();
        btnConfirm = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        setTitle("Thanh toán");

        pnlHeader.setBackground(new java.awt.Color(169, 29, 34));
        pnlHeader.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 18, 12, 18));
        pnlHeader.setLayout(new java.awt.BorderLayout(0, 4));

        lblTitle.setFont(new java.awt.Font("Segoe UI", 1, 18)); // NOI18N
        lblTitle.setForeground(new java.awt.Color(241, 196, 15));
        lblTitle.setText("THANH TOÁN");
        pnlHeader.add(lblTitle, java.awt.BorderLayout.NORTH);

        lblSubtitle.setForeground(new java.awt.Color(245, 214, 214));
        lblSubtitle.setText("-");
        pnlHeader.add(lblSubtitle, java.awt.BorderLayout.CENTER);

        getContentPane().add(pnlHeader, java.awt.BorderLayout.NORTH);

        pnlBody.setBackground(new java.awt.Color(255, 255, 255));
        pnlBody.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 16, 8, 16));
        pnlBody.setLayout(new java.awt.BorderLayout(14, 0));

        tblBill.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        tblBill.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {

            },
            new String [] {
                "STT", "Món", "SL", "Đơn giá", "Thành tiền"
            }
        ) {
            boolean[] canEdit = new boolean [] {
                false, false, false, false, false
            };

            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return canEdit [columnIndex];
            }
        });
        tblBill.setRowHeight(28);
        scrollBill.setViewportView(tblBill);

        pnlBody.add(scrollBill, java.awt.BorderLayout.CENTER);

        pnlPay.setBackground(new java.awt.Color(255, 255, 255));
        pnlPay.setBorder(javax.swing.BorderFactory.createTitledBorder(null, "Thu tiền", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Segoe UI", 1, 12), new java.awt.Color(44, 62, 80))); // NOI18N
        pnlPay.setLayout(new java.awt.GridBagLayout());

        lblSubtotalCaption.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        lblSubtotalCaption.setForeground(new java.awt.Color(44, 62, 80));
        lblSubtotalCaption.setText("Tạm tính");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(5, 4, 5, 8);
        pnlPay.add(lblSubtotalCaption, gridBagConstraints);

        lblSubtotal.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        lblSubtotal.setForeground(new java.awt.Color(44, 62, 80));
        lblSubtotal.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        lblSubtotal.setText("0 đ");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(5, 0, 5, 4);
        pnlPay.add(lblSubtotal, gridBagConstraints);

        lblDiscountCaption.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        lblDiscountCaption.setForeground(new java.awt.Color(44, 62, 80));
        lblDiscountCaption.setText("Giảm giá");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(5, 4, 5, 8);
        pnlPay.add(lblDiscountCaption, gridBagConstraints);

        pnlDiscount.setOpaque(false);
        pnlDiscount.setLayout(new java.awt.BorderLayout(6, 0));

        txtDiscount.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        txtDiscount.setHorizontalAlignment(javax.swing.JTextField.RIGHT);
        txtDiscount.setText("0");
        pnlDiscount.add(txtDiscount, java.awt.BorderLayout.CENTER);

        cboDiscountType.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        cboDiscountType.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "đ", "%" }));
        cboDiscountType.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cboDiscountTypeActionPerformed(evt);
            }
        });
        pnlDiscount.add(cboDiscountType, java.awt.BorderLayout.EAST);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(5, 0, 5, 4);
        pnlPay.add(pnlDiscount, gridBagConstraints);

        lblDepositCaption.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        lblDepositCaption.setForeground(new java.awt.Color(44, 62, 80));
        lblDepositCaption.setText("Đã đặt cọc");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 2;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(5, 4, 5, 8);
        pnlPay.add(lblDepositCaption, gridBagConstraints);

        lblDeposit.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        lblDeposit.setForeground(new java.awt.Color(44, 62, 80));
        lblDeposit.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        lblDeposit.setText("0 đ");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(5, 0, 5, 4);
        pnlPay.add(lblDeposit, gridBagConstraints);

        lblDueCaption.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        lblDueCaption.setForeground(new java.awt.Color(44, 62, 80));
        lblDueCaption.setText("KHÁCH CẦN TRẢ");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 3;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(5, 4, 5, 8);
        pnlPay.add(lblDueCaption, gridBagConstraints);

        lblDue.setFont(new java.awt.Font("Segoe UI", 1, 24)); // NOI18N
        lblDue.setForeground(new java.awt.Color(169, 29, 34));
        lblDue.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        lblDue.setText("0 đ");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 3;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(5, 0, 5, 4);
        pnlPay.add(lblDue, gridBagConstraints);

        lblMethodCaption.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        lblMethodCaption.setForeground(new java.awt.Color(44, 62, 80));
        lblMethodCaption.setText("Hình thức");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 4;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(5, 4, 5, 8);
        pnlPay.add(lblMethodCaption, gridBagConstraints);

        pnlMethods.setOpaque(false);
        pnlMethods.setLayout(new java.awt.GridLayout(2, 1, 0, 2));

        rdoCash.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        rdoCash.setText("Tiền mặt");
        rdoCash.setSelected(true);
        pnlMethods.add(rdoCash);

        rdoTransfer.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        rdoTransfer.setText("Chuyển khoản");
        pnlMethods.add(rdoTransfer);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 4;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(5, 0, 5, 4);
        pnlPay.add(pnlMethods, gridBagConstraints);

        lblCashCaption.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        lblCashCaption.setForeground(new java.awt.Color(44, 62, 80));
        lblCashCaption.setText("Tiền khách đưa");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 5;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(5, 4, 5, 8);
        pnlPay.add(lblCashCaption, gridBagConstraints);

        txtCashGiven.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        txtCashGiven.setHorizontalAlignment(javax.swing.JTextField.RIGHT);
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 5;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(5, 0, 5, 4);
        pnlPay.add(txtCashGiven, gridBagConstraints);

        lblChangeCaption.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        lblChangeCaption.setForeground(new java.awt.Color(44, 62, 80));
        lblChangeCaption.setText("Tiền thối lại");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 6;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(5, 4, 5, 8);
        pnlPay.add(lblChangeCaption, gridBagConstraints);

        lblChange.setFont(new java.awt.Font("Segoe UI", 1, 16)); // NOI18N
        lblChange.setForeground(new java.awt.Color(39, 174, 96));
        lblChange.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        lblChange.setText("0 đ");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 6;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(5, 0, 5, 4);
        pnlPay.add(lblChange, gridBagConstraints);

        lblTransferInfo.setForeground(new java.awt.Color(44, 62, 80));
        lblTransferInfo.setText(" ");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 7;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(8, 4, 4, 4);
        pnlPay.add(lblTransferInfo, gridBagConstraints);

        lblError.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        lblError.setForeground(new java.awt.Color(231, 76, 60));
        lblError.setText(" ");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 8;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.NORTH;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(4, 4, 4, 4);
        pnlPay.add(lblError, gridBagConstraints);

        pnlBody.add(pnlPay, java.awt.BorderLayout.EAST);

        getContentPane().add(pnlBody, java.awt.BorderLayout.CENTER);

        pnlButtons.setBackground(new java.awt.Color(255, 255, 255));
        pnlButtons.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 16, 14, 16));
        pnlButtons.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));

        btnCancel.setBackground(new java.awt.Color(236, 240, 241));
        btnCancel.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        btnCancel.setForeground(new java.awt.Color(44, 62, 80));
        btnCancel.setText("Đóng");
        btnCancel.setFocusable(false);
        btnCancel.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnCancelActionPerformed(evt);
            }
        });
        pnlButtons.add(btnCancel);

        btnConfirm.setBackground(new java.awt.Color(39, 174, 96));
        btnConfirm.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        btnConfirm.setForeground(new java.awt.Color(255, 255, 255));
        btnConfirm.setText("Xác nhận đã thu tiền");
        btnConfirm.setFocusable(false);
        btnConfirm.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnConfirmActionPerformed(evt);
            }
        });
        pnlButtons.add(btnConfirm);

        getContentPane().add(pnlButtons, java.awt.BorderLayout.SOUTH);

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void cboDiscountTypeActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cboDiscountTypeActionPerformed
        recalc();
    }//GEN-LAST:event_cboDiscountTypeActionPerformed

    private void btnCancelActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnCancelActionPerformed
        dispose();
    }//GEN-LAST:event_btnCancelActionPerformed

    private void btnConfirmActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnConfirmActionPerformed
        String method = rdoTransfer.isSelected() ? RestaurantService.PAY_TRANSFER : RestaurantService.PAY_CASH;
        int ok = JOptionPane.showConfirmDialog(this, "Xác nhận đã thu " + Theme.money(due()) + " (" + method + ") của " + owner.tableName(tableId) + "?",
                "Xác nhận thanh toán", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        btnConfirm.setEnabled(false);
        owner.send(NetworkPacket.buildCheckout(tableId, discountAmount(), method));
    }//GEN-LAST:event_btnConfirmActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnCancel;
    private javax.swing.JButton btnConfirm;
    private javax.swing.JComboBox<String> cboDiscountType;
    private javax.swing.JLabel lblCashCaption;
    private javax.swing.JLabel lblChange;
    private javax.swing.JLabel lblChangeCaption;
    private javax.swing.JLabel lblDeposit;
    private javax.swing.JLabel lblDepositCaption;
    private javax.swing.JLabel lblDiscountCaption;
    private javax.swing.JLabel lblDue;
    private javax.swing.JLabel lblDueCaption;
    private javax.swing.JLabel lblError;
    private javax.swing.JLabel lblMethodCaption;
    private javax.swing.JLabel lblSubtitle;
    private javax.swing.JLabel lblSubtotal;
    private javax.swing.JLabel lblSubtotalCaption;
    private javax.swing.JLabel lblTitle;
    private javax.swing.JLabel lblTransferInfo;
    private javax.swing.JPanel pnlBody;
    private javax.swing.JPanel pnlButtons;
    private javax.swing.JPanel pnlDiscount;
    private javax.swing.JPanel pnlHeader;
    private javax.swing.JPanel pnlMethods;
    private javax.swing.JPanel pnlPay;
    private javax.swing.JRadioButton rdoCash;
    private javax.swing.JRadioButton rdoTransfer;
    private javax.swing.JScrollPane scrollBill;
    private javax.swing.JTable tblBill;
    private javax.swing.JTextField txtCashGiven;
    private javax.swing.JTextField txtDiscount;
    // End of variables declaration//GEN-END:variables
}
