/*
 * WalkInDialog.java
 */
package com.haidilao.pos;

import com.haidilao.pos.client.MessageListener;
import com.haidilao.pos.model.DiningTable;
import com.haidilao.pos.protocol.NetworkPacket;
import com.haidilao.pos.ui.Theme;
import javax.swing.JOptionPane;
import javax.swing.table.DefaultTableModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Xếp bàn cho khách vãng lai: nhập số khách, chương trình gợi ý bàn trống đủ ghế (vừa chỗ nhất lên đầu), mở bàn.
 */
public class WalkInDialog extends javax.swing.JDialog implements MessageListener {

    private final ReceptionFrame owner;
    private final List<String[]> suggestions = new ArrayList<>();
    private final List<Integer> waiterIds = new ArrayList<>();
    private String openingTable;

    public WalkInDialog(ReceptionFrame owner) {
        super(owner, true);
        this.owner = owner;
        initComponents();
        Set<String> areas = new TreeSet<>();
        for (String[] i : owner.getTableInfos()) areas.add(i[4]);
        for (String a : areas) cboArea.addItem(a);
        waiterIds.add(0);
        for (Map.Entry<Integer, String> w : owner.getWaiters().entrySet()) {
            cboWaiter.addItem(owner.waiterLabel(w.getKey()));
            waiterIds.add(w.getKey());
        }
        Theme.styleTable(tblSuggest, -1);
        spnGuests.addChangeListener(e -> refresh());
        tblSuggest.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) openSelected();
            }
        });
        refresh();
        owner.addListener(this);
        setSize(620, 560);
        setLocationRelativeTo(owner);
    }

    @Override
    public void dispose() {
        owner.removeListener(this);
        super.dispose();
    }

    /** Gợi ý bàn: đang trống / chỉ đặt trước, đủ ghế, đúng khu vực; bàn vừa chỗ nhất và không vướng lượt đặt lên đầu */
    private void refresh() {
        int guests = (Integer) spnGuests.getValue();
        String area = (String) cboArea.getSelectedItem();
        suggestions.clear();
        for (String[] i : owner.getTableInfos()) {
            if (!"0".equals(i[6]) || DiningTable.STATUS_CLEANING.equals(i[5])) continue;
            if (NetworkPacket.parseInt(i[3], 0) < guests) continue;
            if (area != null && !"Bất kỳ".equals(area) && !area.equals(i[4])) continue;
            suggestions.add(i);
        }
        suggestions.sort(Comparator.<String[]>comparingInt(i -> i[12].isEmpty() ? 0 : 1)
                .thenComparingInt(i -> NetworkPacket.parseInt(i[3], 0)));
        DefaultTableModel m = (DefaultTableModel) tblSuggest.getModel();
        m.setRowCount(0);
        for (String[] i : suggestions) {
            int spare = NetworkPacket.parseInt(i[3], 0) - guests;
            String note = !i[12].isEmpty() ? "Có khách đặt " + i[12] : (spare == 0 ? "Vừa đủ chỗ" : "Thừa " + spare + " ghế");
            m.addRow(new Object[]{i[2], i[4], i[3], note});
        }
        if (!suggestions.isEmpty()) tblSuggest.setRowSelectionInterval(0, 0);
        lblSubtitle.setText(suggestions.isEmpty() ? "Không còn bàn trống đủ " + guests + " ghế" : suggestions.size() + " bàn phù hợp cho " + guests + " khách");
        btnOpen.setEnabled(!suggestions.isEmpty());
    }

    private void openSelected() {
        int row = tblSuggest.getSelectedRow();
        if (row < 0) return;
        if (txtName.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập tên khách để biết ai đang dùng bàn.", "Thiếu thông tin", JOptionPane.WARNING_MESSAGE);
            txtName.requestFocusInWindow();
            return;
        }
        String[] t = suggestions.get(row);
        if (!t[12].isEmpty() && JOptionPane.showConfirmDialog(this, t[2] + " có khách đặt " + t[12] + ".\nVẫn xếp khách vào bàn này?",
                "Bàn sắp có khách đặt", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        openingTable = t[1];
        btnOpen.setEnabled(false);
        owner.send(NetworkPacket.buildSaveSession(t[1], txtName.getText().trim(), txtPhone.getText().trim(), (Integer) spnGuests.getValue(),
                waiterIds.get(Math.max(0, cboWaiter.getSelectedIndex())), ""));
    }

    @Override
    public void onServerMessage(String[] f, String raw) {
        if (openingTable == null || f.length < 2 || !openingTable.equals(f[1])) return;
        if ("SESSION_SAVED".equals(f[0])) {
            owner.toast("Đã mở " + owner.tableName(openingTable) + " cho " + spnGuests.getValue() + " khách.");
            dispose();
        } else if ("SESSION_SAVE_FAIL".equals(f[0])) {
            JOptionPane.showMessageDialog(this, NetworkPacket.dec(f[2]), "Chưa mở được bàn", JOptionPane.WARNING_MESSAGE);
            btnOpen.setEnabled(true);
            openingTable = null;
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
        pnlForm = new javax.swing.JPanel();
        lblGuests = new javax.swing.JLabel();
        spnGuests = new javax.swing.JSpinner();
        lblArea = new javax.swing.JLabel();
        cboArea = new javax.swing.JComboBox<>();
        lblName = new javax.swing.JLabel();
        txtName = new javax.swing.JTextField();
        lblPhone = new javax.swing.JLabel();
        txtPhone = new javax.swing.JTextField();
        lblWaiter = new javax.swing.JLabel();
        cboWaiter = new javax.swing.JComboBox<>();
        scrollSuggest = new javax.swing.JScrollPane();
        tblSuggest = new javax.swing.JTable();
        lblHint = new javax.swing.JLabel();
        pnlButtons = new javax.swing.JPanel();
        btnCancel = new javax.swing.JButton();
        btnOpen = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        setTitle("Xếp bàn cho khách");

        pnlHeader.setBackground(new java.awt.Color(39, 174, 96));
        pnlHeader.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 18, 12, 18));
        pnlHeader.setLayout(new java.awt.BorderLayout(0, 4));

        lblTitle.setFont(new java.awt.Font("Segoe UI", 1, 18)); // NOI18N
        lblTitle.setForeground(new java.awt.Color(255, 255, 255));
        lblTitle.setText("XẾP BÀN CHO KHÁCH");
        pnlHeader.add(lblTitle, java.awt.BorderLayout.NORTH);

        lblSubtitle.setForeground(new java.awt.Color(213, 245, 227));
        lblSubtitle.setText("Khách đến không đặt trước: chọn bàn trống phù hợp");
        pnlHeader.add(lblSubtitle, java.awt.BorderLayout.CENTER);

        getContentPane().add(pnlHeader, java.awt.BorderLayout.NORTH);

        pnlBody.setBackground(new java.awt.Color(255, 255, 255));
        pnlBody.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 18, 6, 18));
        pnlBody.setLayout(new java.awt.BorderLayout(0, 8));

        pnlForm.setOpaque(false);
        pnlForm.setLayout(new java.awt.GridBagLayout());

        lblGuests.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblGuests.setForeground(new java.awt.Color(44, 62, 80));
        lblGuests.setText("Số khách");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(5, 4, 5, 8);
        pnlForm.add(lblGuests, gridBagConstraints);

        spnGuests.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        spnGuests.setModel(new javax.swing.SpinnerNumberModel(2, 1, 99, 1));
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(5, 0, 5, 4);
        pnlForm.add(spnGuests, gridBagConstraints);

        lblArea.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblArea.setForeground(new java.awt.Color(44, 62, 80));
        lblArea.setText("Khu vực");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(5, 4, 5, 8);
        pnlForm.add(lblArea, gridBagConstraints);

        cboArea.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        cboArea.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "Bất kỳ" }));
        cboArea.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cboAreaActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(5, 0, 5, 4);
        pnlForm.add(cboArea, gridBagConstraints);

        lblName.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblName.setForeground(new java.awt.Color(44, 62, 80));
        lblName.setText("Tên khách *");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 2;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(5, 4, 5, 8);
        pnlForm.add(lblName, gridBagConstraints);

        txtName.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(5, 0, 5, 4);
        pnlForm.add(txtName, gridBagConstraints);

        lblPhone.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblPhone.setForeground(new java.awt.Color(44, 62, 80));
        lblPhone.setText("Số điện thoại");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 3;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(5, 4, 5, 8);
        pnlForm.add(lblPhone, gridBagConstraints);

        txtPhone.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 3;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(5, 0, 5, 4);
        pnlForm.add(txtPhone, gridBagConstraints);

        lblWaiter.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblWaiter.setForeground(new java.awt.Color(44, 62, 80));
        lblWaiter.setText("Nhân viên phục vụ");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 4;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(5, 4, 5, 8);
        pnlForm.add(lblWaiter, gridBagConstraints);

        cboWaiter.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        cboWaiter.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "(Chưa gán)" }));
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 4;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(5, 0, 5, 4);
        pnlForm.add(cboWaiter, gridBagConstraints);

        pnlBody.add(pnlForm, java.awt.BorderLayout.NORTH);

        tblSuggest.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        tblSuggest.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {

            },
            new String [] {
                "Bàn", "Khu vực", "Số ghế", "Gợi ý"
            }
        ) {
            boolean[] canEdit = new boolean [] {
                false, false, false, false
            };

            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return canEdit [columnIndex];
            }
        });
        tblSuggest.setRowHeight(28);
        scrollSuggest.setViewportView(tblSuggest);

        pnlBody.add(scrollSuggest, java.awt.BorderLayout.CENTER);

        lblHint.setFont(new java.awt.Font("Segoe UI", 0, 11)); // NOI18N
        lblHint.setForeground(new java.awt.Color(127, 140, 141));
        lblHint.setText("Bàn vừa đủ chỗ được gợi ý trước để dành bàn lớn cho nhóm đông. Bàn sắp có khách đặt nằm cuối danh sách.");
        pnlBody.add(lblHint, java.awt.BorderLayout.SOUTH);

        getContentPane().add(pnlBody, java.awt.BorderLayout.CENTER);

        pnlButtons.setBackground(new java.awt.Color(255, 255, 255));
        pnlButtons.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 18, 14, 18));
        pnlButtons.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));

        btnCancel.setBackground(new java.awt.Color(236, 240, 241));
        btnCancel.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        btnCancel.setForeground(new java.awt.Color(44, 62, 80));
        btnCancel.setText("Hủy");
        btnCancel.setFocusable(false);
        btnCancel.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnCancelActionPerformed(evt);
            }
        });
        pnlButtons.add(btnCancel);

        btnOpen.setBackground(new java.awt.Color(39, 174, 96));
        btnOpen.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        btnOpen.setForeground(new java.awt.Color(255, 255, 255));
        btnOpen.setText("Mở bàn");
        btnOpen.setFocusable(false);
        btnOpen.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnOpenActionPerformed(evt);
            }
        });
        pnlButtons.add(btnOpen);

        getContentPane().add(pnlButtons, java.awt.BorderLayout.SOUTH);

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void cboAreaActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cboAreaActionPerformed
        refresh();
    }//GEN-LAST:event_cboAreaActionPerformed

    private void btnCancelActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnCancelActionPerformed
        dispose();
    }//GEN-LAST:event_btnCancelActionPerformed

    private void btnOpenActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnOpenActionPerformed
        openSelected();
    }//GEN-LAST:event_btnOpenActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnCancel;
    private javax.swing.JButton btnOpen;
    private javax.swing.JComboBox<String> cboArea;
    private javax.swing.JComboBox<String> cboWaiter;
    private javax.swing.JLabel lblArea;
    private javax.swing.JLabel lblGuests;
    private javax.swing.JLabel lblHint;
    private javax.swing.JLabel lblName;
    private javax.swing.JLabel lblPhone;
    private javax.swing.JLabel lblSubtitle;
    private javax.swing.JLabel lblTitle;
    private javax.swing.JLabel lblWaiter;
    private javax.swing.JPanel pnlBody;
    private javax.swing.JPanel pnlButtons;
    private javax.swing.JPanel pnlForm;
    private javax.swing.JPanel pnlHeader;
    private javax.swing.JScrollPane scrollSuggest;
    private javax.swing.JSpinner spnGuests;
    private javax.swing.JTable tblSuggest;
    private javax.swing.JTextField txtName;
    private javax.swing.JTextField txtPhone;
    // End of variables declaration//GEN-END:variables
}
