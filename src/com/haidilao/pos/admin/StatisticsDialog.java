/*
 * StatisticsDialog.java
 */
package com.haidilao.pos.admin;

import com.haidilao.pos.database.StatisticsDao;
import com.haidilao.pos.model.StatRow;
import com.haidilao.pos.ui.BarChartPanel;
import com.haidilao.pos.ui.Theme;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;

/**
 * Thống kê theo khoảng ngày: doanh thu, số đơn, số khách, trung bình mỗi lượt; biểu đồ doanh thu theo ngày,
 * món bán chạy, theo bàn, theo nhân viên phục vụ; xuất CSV.
 */
public class StatisticsDialog extends javax.swing.JDialog {

    private static final SimpleDateFormat DAY = new SimpleDateFormat("dd/MM/yyyy");
    private BarChartPanel chart;

    public StatisticsDialog(java.awt.Frame parent) {
        super(parent, true);
        initComponents();
        chart = new BarChartPanel();
        pnlChartHost.setLayout(new java.awt.BorderLayout());
        pnlChartHost.add(chart, java.awt.BorderLayout.CENTER);
        for (JTable t : new JTable[]{tblDaily, tblTopItems, tblByTable, tblByStaff}) Theme.styleTable(t, -1);
        DAY.setLenient(false);
        cboQuickRange.setSelectedIndex(1);
        setSize(1100, 720);
        setLocationRelativeTo(parent);
    }

    /** Điền ngày theo lựa chọn nhanh rồi xem luôn */
    private void applyQuickRange() {
        Calendar from = Calendar.getInstance();
        Calendar to = Calendar.getInstance();
        switch (cboQuickRange.getSelectedIndex()) {
            case 1: from.add(Calendar.DAY_OF_MONTH, -6); break;
            case 2: from.set(Calendar.DAY_OF_MONTH, 1); break;
            case 3:
                from.add(Calendar.MONTH, -1);
                from.set(Calendar.DAY_OF_MONTH, 1);
                to.setTime(from.getTime());
                to.set(Calendar.DAY_OF_MONTH, to.getActualMaximum(Calendar.DAY_OF_MONTH));
                break;
            case 4: from.set(2020, Calendar.JANUARY, 1); break;
            default: break;
        }
        txtFromDate.setText(DAY.format(from.getTime()));
        txtToDate.setText(DAY.format(to.getTime()));
        loadStatistics();
    }

    private void loadStatistics() {
        java.sql.Date from, to;
        try {
            from = new java.sql.Date(DAY.parse(txtFromDate.getText().trim()).getTime());
            to = new java.sql.Date(DAY.parse(txtToDate.getText().trim()).getTime());
        } catch (ParseException e) {
            JOptionPane.showMessageDialog(this, "Ngày không hợp lệ, nhập theo dạng dd/MM/yyyy.", "Thống kê", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (from.after(to)) {
            JOptionPane.showMessageDialog(this, "Ngày bắt đầu phải trước ngày kết thúc.", "Thống kê", JOptionPane.WARNING_MESSAGE);
            return;
        }
        StatisticsDao.Summary s = StatisticsDao.getSummary(from, to);
        lblRevenue.setText(Theme.money(s.revenue));
        lblOrderCount.setText(String.valueOf(s.orderCount));
        lblGuestCount.setText(s.sessionCount + " lượt / " + s.guestCount + " khách");
        lblAvgOrder.setText(Theme.money(s.getAveragePerSession()));

        List<StatRow> daily = StatisticsDao.getDaily(from, to);
        fill(tblDaily, daily, false);
        chart.setData(daily);
        fill(tblTopItems, StatisticsDao.getTopItems(from, to, 20), true);
        fill(tblByTable, StatisticsDao.getByTable(from, to), false);
        fill(tblByStaff, StatisticsDao.getByWaiter(from, to), false);
    }

    private static void fill(JTable table, List<StatRow> rows, boolean ranked) {
        DefaultTableModel m = (DefaultTableModel) table.getModel();
        m.setRowCount(0);
        int rank = 1;
        for (StatRow r : rows) {
            if (ranked) m.addRow(new Object[]{rank++, r.getLabel(), r.getCount(), Theme.money(r.getAmount())});
            else m.addRow(new Object[]{r.getLabel(), r.getCount(), Theme.money(r.getAmount())});
        }
    }

    /** Xuất bảng đang xem ra file CSV (mở được bằng Excel) */
    private void exportCsv() {
        JTable table = (JTable) ((JScrollPane) (tabStats.getSelectedIndex() == 0 ? scrollDaily : tabStats.getSelectedComponent())).getViewport().getView();
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("thong-ke-" + tabStats.getTitleAt(tabStats.getSelectedIndex()).replace(' ', '-') + ".csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try (Writer w = new OutputStreamWriter(new FileOutputStream(fc.getSelectedFile()), StandardCharsets.UTF_8)) {
            w.write('﻿'); // BOM để Excel đọc đúng tiếng Việt
            for (int c = 0; c < table.getColumnCount(); c++) w.write((c > 0 ? "," : "") + csv(table.getColumnName(c)));
            w.write("\n");
            for (int r = 0; r < table.getRowCount(); r++) {
                for (int c = 0; c < table.getColumnCount(); c++) w.write((c > 0 ? "," : "") + csv(String.valueOf(table.getValueAt(r, c))));
                w.write("\n");
            }
            JOptionPane.showMessageDialog(this, "Đã xuất " + fc.getSelectedFile().getName());
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
        pnlBody = new javax.swing.JPanel();
        pnlTop = new javax.swing.JPanel();
        pnlFilter = new javax.swing.JPanel();
        lblFrom = new javax.swing.JLabel();
        txtFromDate = new javax.swing.JTextField();
        lblTo = new javax.swing.JLabel();
        txtToDate = new javax.swing.JTextField();
        cboQuickRange = new javax.swing.JComboBox<>();
        btnView = new javax.swing.JButton();
        btnExport = new javax.swing.JButton();
        pnlKpis = new javax.swing.JPanel();
        pnlRevenue = new javax.swing.JPanel();
        lblRevenueTitle = new javax.swing.JLabel();
        lblRevenue = new javax.swing.JLabel();
        pnlOrderCount = new javax.swing.JPanel();
        lblOrderCountTitle = new javax.swing.JLabel();
        lblOrderCount = new javax.swing.JLabel();
        pnlGuestCount = new javax.swing.JPanel();
        lblGuestCountTitle = new javax.swing.JLabel();
        lblGuestCount = new javax.swing.JLabel();
        pnlAvgOrder = new javax.swing.JPanel();
        lblAvgOrderTitle = new javax.swing.JLabel();
        lblAvgOrder = new javax.swing.JLabel();
        tabStats = new javax.swing.JTabbedPane();
        splitDaily = new javax.swing.JSplitPane();
        pnlChartHost = new javax.swing.JPanel();
        scrollDaily = new javax.swing.JScrollPane();
        tblDaily = new javax.swing.JTable();
        scrollTopItems = new javax.swing.JScrollPane();
        tblTopItems = new javax.swing.JTable();
        scrollByTable = new javax.swing.JScrollPane();
        tblByTable = new javax.swing.JTable();
        scrollByStaff = new javax.swing.JScrollPane();
        tblByStaff = new javax.swing.JTable();

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        setTitle("Thống kê doanh thu");
        getContentPane().setLayout(new java.awt.BorderLayout());

        pnlHeader.setBackground(new java.awt.Color(31, 36, 48));
        pnlHeader.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 16, 12, 16));
        pnlHeader.setLayout(new java.awt.BorderLayout(12, 0));

        lblTitle.setFont(new java.awt.Font("Segoe UI", 1, 17)); // NOI18N
        lblTitle.setForeground(new java.awt.Color(241, 196, 15));
        lblTitle.setText("THỐNG KÊ DOANH THU");
        pnlHeader.add(lblTitle, java.awt.BorderLayout.WEST);

        getContentPane().add(pnlHeader, java.awt.BorderLayout.NORTH);

        pnlBody.setBackground(new java.awt.Color(244, 245, 247));
        pnlBody.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 12, 10, 12));
        pnlBody.setLayout(new java.awt.BorderLayout(0, 10));

        pnlTop.setOpaque(false);
        pnlTop.setLayout(new java.awt.BorderLayout(0, 10));

        pnlFilter.setOpaque(false);
        pnlFilter.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));

        lblFrom.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblFrom.setForeground(new java.awt.Color(44, 62, 80));
        lblFrom.setText("Từ ngày");
        pnlFilter.add(lblFrom);

        txtFromDate.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        txtFromDate.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        txtFromDate.setColumns(9);
        pnlFilter.add(txtFromDate);

        lblTo.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblTo.setForeground(new java.awt.Color(44, 62, 80));
        lblTo.setText("đến ngày");
        pnlFilter.add(lblTo);

        txtToDate.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        txtToDate.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        txtToDate.setColumns(9);
        pnlFilter.add(txtToDate);

        cboQuickRange.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        cboQuickRange.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "Hôm nay", "7 ngày qua", "Tháng này", "Tháng trước", "Tất cả" }));
        cboQuickRange.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cboQuickRangeActionPerformed(evt);
            }
        });
        pnlFilter.add(cboQuickRange);

        btnView.setBackground(new java.awt.Color(169, 29, 34));
        btnView.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnView.setForeground(new java.awt.Color(255, 255, 255));
        btnView.setText("Xem thống kê");
        btnView.setFocusable(false);
        btnView.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnViewActionPerformed(evt);
            }
        });
        pnlFilter.add(btnView);

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

        pnlTop.add(pnlFilter, java.awt.BorderLayout.NORTH);

        pnlKpis.setOpaque(false);
        pnlKpis.setLayout(new java.awt.GridLayout(1, 4, 12, 0));

        pnlRevenue.setBackground(new java.awt.Color(255, 255, 255));
        pnlRevenue.setBorder(javax.swing.BorderFactory.createCompoundBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(222, 226, 232)), javax.swing.BorderFactory.createEmptyBorder(10, 14, 10, 14)));
        pnlRevenue.setLayout(new java.awt.BorderLayout(0, 4));

        lblRevenueTitle.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        lblRevenueTitle.setForeground(new java.awt.Color(127, 140, 141));
        lblRevenueTitle.setText("Doanh thu");
        pnlRevenue.add(lblRevenueTitle, java.awt.BorderLayout.NORTH);

        lblRevenue.setFont(new java.awt.Font("Segoe UI", 1, 20)); // NOI18N
        lblRevenue.setForeground(new java.awt.Color(169, 29, 34));
        lblRevenue.setText("-");
        pnlRevenue.add(lblRevenue, java.awt.BorderLayout.CENTER);

        pnlKpis.add(pnlRevenue);

        pnlOrderCount.setBackground(new java.awt.Color(255, 255, 255));
        pnlOrderCount.setBorder(javax.swing.BorderFactory.createCompoundBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(222, 226, 232)), javax.swing.BorderFactory.createEmptyBorder(10, 14, 10, 14)));
        pnlOrderCount.setLayout(new java.awt.BorderLayout(0, 4));

        lblOrderCountTitle.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        lblOrderCountTitle.setForeground(new java.awt.Color(127, 140, 141));
        lblOrderCountTitle.setText("Số đơn (không tính đơn hủy)");
        pnlOrderCount.add(lblOrderCountTitle, java.awt.BorderLayout.NORTH);

        lblOrderCount.setFont(new java.awt.Font("Segoe UI", 1, 20)); // NOI18N
        lblOrderCount.setForeground(new java.awt.Color(41, 128, 185));
        lblOrderCount.setText("-");
        pnlOrderCount.add(lblOrderCount, java.awt.BorderLayout.CENTER);

        pnlKpis.add(pnlOrderCount);

        pnlGuestCount.setBackground(new java.awt.Color(255, 255, 255));
        pnlGuestCount.setBorder(javax.swing.BorderFactory.createCompoundBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(222, 226, 232)), javax.swing.BorderFactory.createEmptyBorder(10, 14, 10, 14)));
        pnlGuestCount.setLayout(new java.awt.BorderLayout(0, 4));

        lblGuestCountTitle.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        lblGuestCountTitle.setForeground(new java.awt.Color(127, 140, 141));
        lblGuestCountTitle.setText("Số lượt khách / số khách");
        pnlGuestCount.add(lblGuestCountTitle, java.awt.BorderLayout.NORTH);

        lblGuestCount.setFont(new java.awt.Font("Segoe UI", 1, 20)); // NOI18N
        lblGuestCount.setForeground(new java.awt.Color(39, 174, 96));
        lblGuestCount.setText("-");
        pnlGuestCount.add(lblGuestCount, java.awt.BorderLayout.CENTER);

        pnlKpis.add(pnlGuestCount);

        pnlAvgOrder.setBackground(new java.awt.Color(255, 255, 255));
        pnlAvgOrder.setBorder(javax.swing.BorderFactory.createCompoundBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(222, 226, 232)), javax.swing.BorderFactory.createEmptyBorder(10, 14, 10, 14)));
        pnlAvgOrder.setLayout(new java.awt.BorderLayout(0, 4));

        lblAvgOrderTitle.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        lblAvgOrderTitle.setForeground(new java.awt.Color(127, 140, 141));
        lblAvgOrderTitle.setText("Trung bình mỗi lượt khách");
        pnlAvgOrder.add(lblAvgOrderTitle, java.awt.BorderLayout.NORTH);

        lblAvgOrder.setFont(new java.awt.Font("Segoe UI", 1, 20)); // NOI18N
        lblAvgOrder.setForeground(new java.awt.Color(142, 68, 173));
        lblAvgOrder.setText("-");
        pnlAvgOrder.add(lblAvgOrder, java.awt.BorderLayout.CENTER);

        pnlKpis.add(pnlAvgOrder);

        pnlTop.add(pnlKpis, java.awt.BorderLayout.CENTER);

        pnlBody.add(pnlTop, java.awt.BorderLayout.NORTH);

        tabStats.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        splitDaily.setOrientation(javax.swing.JSplitPane.VERTICAL_SPLIT);
        splitDaily.setResizeWeight(0.6);
        pnlChartHost.setBackground(new java.awt.Color(255, 255, 255));
        pnlChartHost.setLayout(new java.awt.BorderLayout());

        splitDaily.setTopComponent(pnlChartHost);

        tblDaily.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        tblDaily.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {

            },
            new String [] {
                "Ngày", "Số đơn", "Doanh thu"
            }
        ) {
            boolean[] canEdit = new boolean [] {
                false, false, false
            };

            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return canEdit [columnIndex];
            }
        });
        tblDaily.setRowHeight(28);
        scrollDaily.setViewportView(tblDaily);

        splitDaily.setBottomComponent(scrollDaily);

        tabStats.addTab("Theo ngày", splitDaily);

        tblTopItems.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        tblTopItems.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {

            },
            new String [] {
                "Hạng", "Tên món / combo", "Số lượng", "Doanh thu"
            }
        ) {
            boolean[] canEdit = new boolean [] {
                false, false, false, false
            };

            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return canEdit [columnIndex];
            }
        });
        tblTopItems.setRowHeight(28);
        scrollTopItems.setViewportView(tblTopItems);

        tabStats.addTab("Món bán chạy", scrollTopItems);

        tblByTable.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        tblByTable.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {

            },
            new String [] {
                "Bàn", "Số đơn", "Doanh thu"
            }
        ) {
            boolean[] canEdit = new boolean [] {
                false, false, false
            };

            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return canEdit [columnIndex];
            }
        });
        tblByTable.setRowHeight(28);
        scrollByTable.setViewportView(tblByTable);

        tabStats.addTab("Theo bàn", scrollByTable);

        tblByStaff.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        tblByStaff.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {

            },
            new String [] {
                "Nhân viên", "Số lượt phục vụ", "Doanh thu"
            }
        ) {
            boolean[] canEdit = new boolean [] {
                false, false, false
            };

            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return canEdit [columnIndex];
            }
        });
        tblByStaff.setRowHeight(28);
        scrollByStaff.setViewportView(tblByStaff);

        tabStats.addTab("Theo nhân viên phục vụ", scrollByStaff);

        pnlBody.add(tabStats, java.awt.BorderLayout.CENTER);

        getContentPane().add(pnlBody, java.awt.BorderLayout.CENTER);

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void cboQuickRangeActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cboQuickRangeActionPerformed
        applyQuickRange();
    }//GEN-LAST:event_cboQuickRangeActionPerformed

    private void btnViewActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnViewActionPerformed
        loadStatistics();
    }//GEN-LAST:event_btnViewActionPerformed

    private void btnExportActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnExportActionPerformed
        exportCsv();
    }//GEN-LAST:event_btnExportActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JPanel pnlHeader;
    private javax.swing.JLabel lblTitle;
    private javax.swing.JPanel pnlBody;
    private javax.swing.JPanel pnlTop;
    private javax.swing.JPanel pnlFilter;
    private javax.swing.JLabel lblFrom;
    private javax.swing.JTextField txtFromDate;
    private javax.swing.JLabel lblTo;
    private javax.swing.JTextField txtToDate;
    private javax.swing.JComboBox<String> cboQuickRange;
    private javax.swing.JButton btnView;
    private javax.swing.JButton btnExport;
    private javax.swing.JPanel pnlKpis;
    private javax.swing.JPanel pnlRevenue;
    private javax.swing.JLabel lblRevenueTitle;
    private javax.swing.JLabel lblRevenue;
    private javax.swing.JPanel pnlOrderCount;
    private javax.swing.JLabel lblOrderCountTitle;
    private javax.swing.JLabel lblOrderCount;
    private javax.swing.JPanel pnlGuestCount;
    private javax.swing.JLabel lblGuestCountTitle;
    private javax.swing.JLabel lblGuestCount;
    private javax.swing.JPanel pnlAvgOrder;
    private javax.swing.JLabel lblAvgOrderTitle;
    private javax.swing.JLabel lblAvgOrder;
    private javax.swing.JTabbedPane tabStats;
    private javax.swing.JSplitPane splitDaily;
    private javax.swing.JPanel pnlChartHost;
    private javax.swing.JScrollPane scrollDaily;
    private javax.swing.JTable tblDaily;
    private javax.swing.JScrollPane scrollTopItems;
    private javax.swing.JTable tblTopItems;
    private javax.swing.JScrollPane scrollByTable;
    private javax.swing.JTable tblByTable;
    private javax.swing.JScrollPane scrollByStaff;
    private javax.swing.JTable tblByStaff;
    // End of variables declaration//GEN-END:variables
}
