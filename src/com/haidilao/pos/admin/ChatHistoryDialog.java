/*
 * ChatHistoryDialog.java
 */
package com.haidilao.pos.admin;

import com.haidilao.pos.database.DatabaseManager;
import com.haidilao.pos.database.TableDao;
import com.haidilao.pos.model.ChatMessage;
import com.haidilao.pos.model.DiningTable;
import com.haidilao.pos.ui.Theme;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.table.DefaultTableModel;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Quản lý tra cứu lịch sử tin nhắn / chuông giữa khách và nhân viên theo ngày, bàn, từ khóa hoặc tên nhân viên
 * (giải quyết thắc mắc, khiếu nại); xem nội dung đầy đủ, xuất CSV.
 */
public class ChatHistoryDialog extends javax.swing.JDialog {

    private static final SimpleDateFormat DAY = new SimpleDateFormat("dd/MM/yyyy");
    private final List<String> tableIds = new ArrayList<>();
    private final Map<String, String> tableNames = new HashMap<>();
    private final List<ChatMessage> rows = new ArrayList<>();
    private DefaultTableModel model;

    public ChatHistoryDialog(java.awt.Frame parent) {
        super(parent, true);
        initComponents();
        model = (DefaultTableModel) tblLogs.getModel();
        Theme.styleTable(tblLogs, -1);
        int[] w = {110, 70, 190, 110, 70, 420};
        for (int i = 0; i < w.length; i++) tblLogs.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        for (DiningTable t : TableDao.getAllTables()) {
            tableIds.add(t.getTableId());
            tableNames.put(t.getTableId(), t.getTableName());
            cboTable.addItem(t.getTableName());
        }
        tblLogs.getSelectionModel().addListSelectionListener(e -> {
            int r = tblLogs.getSelectedRow();
            if (!e.getValueIsAdjusting() && r >= 0) {
                ChatMessage m = rows.get(tblLogs.convertRowIndexToModel(r));
                txtDetail.setText(new SimpleDateFormat("HH:mm:ss dd/MM/yyyy").format(m.getSentAt()) + " · " + senderOf(m)
                        + " → " + receiverOf(m) + "\n\n" + m.getContent());
            }
        });
        DAY.setLenient(false);
        String today = DAY.format(new Date());
        txtFromDate.setText(DAY.format(new Date(System.currentTimeMillis() - 6 * 86_400_000L)));
        txtToDate.setText(today);
        setMinimumSize(new java.awt.Dimension(900, 560));
        Theme.fitToScreen(this, 0.85);
        setLocationRelativeTo(parent);
        search();
    }

    /** Bàn liên quan đến dòng nhật ký: khách gửi thì là người gửi, nhân viên gửi thì là người nhận */
    private String tableOf(ChatMessage m) {
        if (tableNames.containsKey(m.getSender())) return m.getSender();
        return m.getReceiver();
    }

    private boolean fromCustomer(ChatMessage m) {
        return tableNames.containsKey(m.getSender());
    }

    private String senderOf(ChatMessage m) {
        if (!m.getSenderName().isEmpty()) return m.getSenderName();
        return fromCustomer(m) ? "Khách " + tableNames.get(m.getSender()) : m.getSender();
    }

    private String receiverOf(ChatMessage m) {
        return fromCustomer(m) ? "Nhân viên" : "Khách " + tableNames.getOrDefault(m.getReceiver(), m.getReceiver());
    }

    private void search() {
        java.sql.Date from, to;
        try {
            from = new java.sql.Date(DAY.parse(txtFromDate.getText().trim()).getTime());
            to = new java.sql.Date(DAY.parse(txtToDate.getText().trim()).getTime());
        } catch (ParseException e) {
            JOptionPane.showMessageDialog(this, "Ngày không hợp lệ, nhập theo dạng dd/MM/yyyy.", "Lịch sử tin nhắn", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int ti = cboTable.getSelectedIndex();
        String table = ti > 0 ? tableIds.get(ti - 1) : null;
        String keyword = txtKeyword.getText().trim().isEmpty() ? null : txtKeyword.getText().trim();
        rows.clear();
        rows.addAll(DatabaseManager.searchChatLogs(from, to, table, keyword, 2000));
        model.setRowCount(0);
        SimpleDateFormat fmt = new SimpleDateFormat("dd/MM HH:mm:ss");
        for (ChatMessage m : rows) {
            String t = tableOf(m);
            model.addRow(new Object[]{m.getSentAt() == null ? "" : fmt.format(m.getSentAt()), tableNames.getOrDefault(t, t), senderOf(m),
                    fromCustomer(m) ? "Khách → NV" : "NV → Khách", "RING".equals(m.getMessageType()) ? "Chuông" : "Tin nhắn", m.getContent()});
        }
        txtDetail.setText("");
        lblCount.setText(rows.size() + " dòng" + (rows.size() >= 2000 ? " (chỉ hiện 2000 dòng gần nhất, hãy thu hẹp điều kiện tìm)" : ""));
    }

    private void exportCsv() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("lich-su-tin-nhan.csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try (Writer w = new OutputStreamWriter(new FileOutputStream(fc.getSelectedFile()), StandardCharsets.UTF_8)) {
            w.write('﻿'); // BOM để Excel đọc đúng tiếng Việt
            for (int c = 0; c < model.getColumnCount(); c++) w.write((c > 0 ? "," : "") + csv(model.getColumnName(c)));
            w.write("\n");
            for (int r = 0; r < model.getRowCount(); r++) {
                for (int c = 0; c < model.getColumnCount(); c++) w.write((c > 0 ? "," : "") + csv(String.valueOf(model.getValueAt(r, c))));
                w.write("\n");
            }
            JOptionPane.showMessageDialog(this, "Đã xuất " + model.getRowCount() + " dòng ra " + fc.getSelectedFile().getName());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Không ghi được file: " + e.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String csv(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
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
        lblTitle = new javax.swing.JLabel();
        lblSubtitle = new javax.swing.JLabel();
        pnlBody = new javax.swing.JPanel();
        pnlFilter = new javax.swing.JPanel();
        lblFrom = new javax.swing.JLabel();
        txtFromDate = new javax.swing.JTextField();
        lblTo = new javax.swing.JLabel();
        txtToDate = new javax.swing.JTextField();
        lblTable = new javax.swing.JLabel();
        cboTable = new javax.swing.JComboBox<>();
        lblKeyword = new javax.swing.JLabel();
        txtKeyword = new javax.swing.JTextField();
        btnSearch = new javax.swing.JButton();
        btnExport = new javax.swing.JButton();
        splitLog = new javax.swing.JSplitPane();
        scrollLogs = new javax.swing.JScrollPane();
        tblLogs = new javax.swing.JTable();
        pnlDetail = new javax.swing.JPanel();
        scrollDetail = new javax.swing.JScrollPane();
        txtDetail = new javax.swing.JTextArea();
        lblCount = new javax.swing.JLabel();

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        setTitle("Lịch sử tin nhắn");

        pnlHeader.setBackground(new java.awt.Color(31, 36, 48));
        pnlHeader.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 16, 12, 16));
        pnlHeader.setLayout(new java.awt.BorderLayout(12, 0));

        lblTitle.setFont(new java.awt.Font("Segoe UI", 1, 17)); // NOI18N
        lblTitle.setForeground(new java.awt.Color(241, 196, 15));
        lblTitle.setText("LỊCH SỬ TIN NHẮN");
        pnlHeader.add(lblTitle, java.awt.BorderLayout.WEST);

        lblSubtitle.setForeground(new java.awt.Color(200, 206, 216));
        lblSubtitle.setText("Tra cứu hội thoại giữa khách và nhân viên khi có thắc mắc / khiếu nại");
        pnlHeader.add(lblSubtitle, java.awt.BorderLayout.EAST);

        getContentPane().add(pnlHeader, java.awt.BorderLayout.NORTH);

        pnlBody.setBackground(new java.awt.Color(244, 245, 247));
        pnlBody.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 12, 10, 12));
        pnlBody.setLayout(new java.awt.BorderLayout(0, 8));

        pnlFilter.setOpaque(false);
        pnlFilter.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));

        lblFrom.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblFrom.setForeground(new java.awt.Color(44, 62, 80));
        lblFrom.setText("Từ ngày");
        pnlFilter.add(lblFrom);

        txtFromDate.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        txtFromDate.setHorizontalAlignment(javax.swing.JTextField.CENTER);
        txtFromDate.setColumns(9);
        pnlFilter.add(txtFromDate);

        lblTo.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblTo.setForeground(new java.awt.Color(44, 62, 80));
        lblTo.setText("đến");
        pnlFilter.add(lblTo);

        txtToDate.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        txtToDate.setHorizontalAlignment(javax.swing.JTextField.CENTER);
        txtToDate.setColumns(9);
        pnlFilter.add(txtToDate);

        lblTable.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblTable.setForeground(new java.awt.Color(44, 62, 80));
        lblTable.setText("Bàn");
        pnlFilter.add(lblTable);

        cboTable.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        cboTable.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "Tất cả các bàn" }));
        pnlFilter.add(cboTable);

        lblKeyword.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblKeyword.setForeground(new java.awt.Color(44, 62, 80));
        lblKeyword.setText("Từ khóa / tên nhân viên");
        pnlFilter.add(lblKeyword);

        txtKeyword.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        txtKeyword.setColumns(14);
        txtKeyword.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                txtKeywordActionPerformed(evt);
            }
        });
        pnlFilter.add(txtKeyword);

        btnSearch.setBackground(new java.awt.Color(169, 29, 34));
        btnSearch.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnSearch.setForeground(new java.awt.Color(255, 255, 255));
        btnSearch.setText("Tìm");
        btnSearch.setFocusable(false);
        btnSearch.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnSearchActionPerformed(evt);
            }
        });
        pnlFilter.add(btnSearch);

        btnExport.setBackground(new java.awt.Color(236, 240, 241));
        btnExport.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnExport.setForeground(new java.awt.Color(44, 62, 80));
        btnExport.setText("Xuất CSV");
        btnExport.setFocusable(false);
        btnExport.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnExportActionPerformed(evt);
            }
        });
        pnlFilter.add(btnExport);

        pnlBody.add(pnlFilter, java.awt.BorderLayout.NORTH);

        splitLog.setOrientation(javax.swing.JSplitPane.VERTICAL_SPLIT);
        splitLog.setResizeWeight(0.78);

        tblLogs.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        tblLogs.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {

            },
            new String [] {
                "Thời gian", "Bàn", "Người gửi", "Chiều", "Loại", "Nội dung"
            }
        ) {
            boolean[] canEdit = new boolean [] {
                false, false, false, false, false, false
            };

            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return canEdit [columnIndex];
            }
        });
        tblLogs.setRowHeight(28);
        scrollLogs.setViewportView(tblLogs);

        splitLog.setTopComponent(scrollLogs);

        pnlDetail.setBackground(new java.awt.Color(255, 255, 255));
        pnlDetail.setBorder(javax.swing.BorderFactory.createTitledBorder(null, "Nội dung đầy đủ", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Segoe UI", 1, 12), new java.awt.Color(44, 62, 80))); // NOI18N
        pnlDetail.setLayout(new java.awt.BorderLayout(0, 6));

        txtDetail.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        txtDetail.setColumns(20);
        txtDetail.setEditable(false);
        txtDetail.setLineWrap(true);
        txtDetail.setRows(4);
        txtDetail.setWrapStyleWord(true);
        scrollDetail.setViewportView(txtDetail);

        pnlDetail.add(scrollDetail, java.awt.BorderLayout.CENTER);

        splitLog.setBottomComponent(pnlDetail);

        pnlBody.add(splitLog, java.awt.BorderLayout.CENTER);

        lblCount.setForeground(new java.awt.Color(127, 140, 141));
        lblCount.setText(" ");
        pnlBody.add(lblCount, java.awt.BorderLayout.SOUTH);

        getContentPane().add(pnlBody, java.awt.BorderLayout.CENTER);

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void txtKeywordActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_txtKeywordActionPerformed
        search();
    }//GEN-LAST:event_txtKeywordActionPerformed

    private void btnSearchActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnSearchActionPerformed
        search();
    }//GEN-LAST:event_btnSearchActionPerformed

    private void btnExportActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnExportActionPerformed
        exportCsv();
    }//GEN-LAST:event_btnExportActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnExport;
    private javax.swing.JButton btnSearch;
    private javax.swing.JComboBox<String> cboTable;
    private javax.swing.JLabel lblCount;
    private javax.swing.JLabel lblFrom;
    private javax.swing.JLabel lblKeyword;
    private javax.swing.JLabel lblSubtitle;
    private javax.swing.JLabel lblTable;
    private javax.swing.JLabel lblTitle;
    private javax.swing.JLabel lblTo;
    private javax.swing.JPanel pnlBody;
    private javax.swing.JPanel pnlDetail;
    private javax.swing.JPanel pnlFilter;
    private javax.swing.JPanel pnlHeader;
    private javax.swing.JScrollPane scrollDetail;
    private javax.swing.JScrollPane scrollLogs;
    private javax.swing.JSplitPane splitLog;
    private javax.swing.JTable tblLogs;
    private javax.swing.JTextArea txtDetail;
    private javax.swing.JTextField txtFromDate;
    private javax.swing.JTextField txtKeyword;
    private javax.swing.JTextField txtToDate;
    // End of variables declaration//GEN-END:variables
}
