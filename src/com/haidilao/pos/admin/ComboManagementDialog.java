/*
 * ComboManagementDialog.java
 */
package com.haidilao.pos.admin;

import com.haidilao.pos.database.ComboDao;
import com.haidilao.pos.database.DatabaseManager;
import com.haidilao.pos.model.Combo;
import com.haidilao.pos.model.MenuItem;
import com.haidilao.pos.server.PosServer;
import com.haidilao.pos.ui.Theme;
import com.haidilao.pos.util.ImageUtil;
import javax.swing.JOptionPane;
import javax.swing.table.DefaultTableModel;
import java.util.ArrayList;
import java.util.List;

/**
 * Quản lý combo: tên, giá, trạng thái, mô tả và các món bên trong (hiện giá lẻ cộng lại để so sánh).
 * Lưu xong báo các Tablet tải lại thực đơn.
 */
public class ComboManagementDialog extends javax.swing.JDialog {

    private final PosServer posServer;
    private DefaultTableModel comboModel;
    private DefaultTableModel itemModel;
    private final List<Combo> combos = new ArrayList<>();
    private final List<MenuItem> menu = new ArrayList<>();
    private byte[] currentImage;            // ảnh combo đang hiện ở khung xem trước
    private static java.io.File lastImageDir;

    /** Chọn ảnh từ máy, thu nhỏ rồi hiện ở khung xem trước (bấm Lưu / Thêm combo mới ghi vào database) */
    private void chooseImage() {
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser(lastImageDir);
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Ảnh (JPG, PNG, GIF, BMP)", "jpg", "jpeg", "png", "gif", "bmp"));
        if (chooser.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        lastImageDir = chooser.getCurrentDirectory();
        try {
            showImage(ImageUtil.createThumbnail(chooser.getSelectedFile()));
            lblComboValue.setText("Đã chọn ảnh, bấm \"Lưu\" (hoặc \"Thêm combo\") để cập nhật lên Tablet.");
        } catch (java.io.IOException ex) {
            JOptionPane.showMessageDialog(this, "Không đọc được ảnh: " + ex.getMessage(), "Lỗi ảnh", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showImage(byte[] data) {
        currentImage = data;
        javax.swing.ImageIcon icon = ImageUtil.toIcon(data);
        lblImagePreview.setIcon(icon);
        lblImagePreview.setText(icon == null ? "Chưa có ảnh" : "");
        btnRemoveImage.setEnabled(icon != null);
    }

    public ComboManagementDialog(java.awt.Frame parent, PosServer posServer) {
        super(parent, true);
        this.posServer = posServer;
        initComponents();
        comboModel = (DefaultTableModel) tblCombos.getModel();
        itemModel = (DefaultTableModel) tblComboItems.getModel();
        Theme.styleTable(tblCombos, -1);
        Theme.styleTable(tblComboItems, -1);
        tblCombos.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showSelected();
        });
        for (MenuItem m : DatabaseManager.getAllMenuItemsAdmin()) {
            menu.add(m);
            cboMenuItem.addItem(m.getItemName() + " - " + Theme.money(m.getPrice()));
        }
        loadCombos();
        clearForm();
        int[] w = {40, 170, 90, 80, 55, 60};
        for (int i = 0; i < w.length; i++) tblCombos.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        lblImagePreview.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) chooseImage();
            }
        });
        setMinimumSize(new java.awt.Dimension(980, 600));
        Theme.fitToScreen(this, 0.9); // vừa màn hình laptop lẫn màn hình lớn
        setLocationRelativeTo(parent);
    }

    private void loadCombos() {
        comboModel.setRowCount(0);
        combos.clear();
        for (Combo c : ComboDao.getAllCombos(false)) {
            combos.add(c);
            comboModel.addRow(new Object[]{c.getComboId(), c.getComboName(), Theme.money(c.getPrice()),
                    "AVAILABLE".equals(c.getStatus()) ? "Đang bán" : "Tạm ngừng", c.getItems().size(),
                    c.getImageData() == null ? "Chưa có" : "Có"});
        }
    }

    private void showSelected() {
        int row = tblCombos.getSelectedRow();
        if (row < 0) return;
        Combo c = combos.get(tblCombos.convertRowIndexToModel(row));
        txtComboId.setText(String.valueOf(c.getComboId()));
        txtComboName.setText(c.getComboName());
        txtComboPrice.setText(String.valueOf(c.getPrice()));
        cboComboStatus.setSelectedIndex("AVAILABLE".equals(c.getStatus()) ? 0 : 1);
        txtComboDesc.setText(c.getDescription() == null ? "" : c.getDescription());
        showImage(c.getImageData());
        itemModel.setRowCount(0);
        for (Combo.ComboItem ci : c.getItems()) itemModel.addRow(new Object[]{ci.getItemId(), ci.getItemName(), ci.getQuantity()});
        btnAdd.setEnabled(false);
        btnUpdate.setEnabled(true);
        btnDelete.setEnabled(true);
        updateValue();
    }

    private void clearForm() {
        tblCombos.clearSelection();
        txtComboId.setText("(tự cấp)");
        txtComboName.setText("");
        txtComboPrice.setText("");
        cboComboStatus.setSelectedIndex(0);
        txtComboDesc.setText("");
        showImage(null);
        itemModel.setRowCount(0);
        btnAdd.setEnabled(true);
        btnUpdate.setEnabled(false);
        btnDelete.setEnabled(false);
        updateValue();
    }

    /** Giá lẻ cộng lại của các món trong combo, để quản lý biết combo rẻ hơn bao nhiêu */
    private void updateValue() {
        long sum = 0;
        for (int r = 0; r < itemModel.getRowCount(); r++) {
            int id = (Integer) itemModel.getValueAt(r, 0);
            int qty = (Integer) itemModel.getValueAt(r, 2);
            for (MenuItem m : menu) if (m.getItemId() == id) sum += m.getPrice() * qty;
        }
        long price = parsePrice();
        lblComboValue.setText(sum == 0 ? " " : "Giá lẻ cộng lại: " + Theme.money(sum)
                + (price > 0 && price < sum ? "  ·  khách tiết kiệm " + Theme.money(sum - price) : ""));
    }

    private long parsePrice() {
        String d = txtComboPrice.getText().replaceAll("[^0-9]", "");
        return d.isEmpty() || d.length() > 10 ? 0 : Long.parseLong(d);
    }

    /** Đọc form thành Combo (id = 0 khi thêm mới), null nếu dữ liệu sai */
    private Combo readForm(int id) {
        String name = txtComboName.getText().trim();
        long price = parsePrice();
        if (name.isEmpty() || name.length() > 120) return warn("Vui lòng nhập tên combo.");
        if (price <= 0) return warn("Giá combo phải lớn hơn 0.");
        if (itemModel.getRowCount() == 0) return warn("Combo phải có ít nhất 1 món.");
        Combo c = new Combo(id, name, price, txtComboDesc.getText().trim(), cboComboStatus.getSelectedIndex() == 0 ? "AVAILABLE" : "UNAVAILABLE");
        c.setImageData(currentImage);
        for (int r = 0; r < itemModel.getRowCount(); r++) {
            c.getItems().add(new Combo.ComboItem((Integer) itemModel.getValueAt(r, 0), String.valueOf(itemModel.getValueAt(r, 1)),
                    (Integer) itemModel.getValueAt(r, 2)));
        }
        return c;
    }

    private Combo warn(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Dữ liệu chưa hợp lệ", JOptionPane.WARNING_MESSAGE);
        return null;
    }

    private void afterChange(String message) {
        loadCombos();
        clearForm();
        if (posServer != null) {
            posServer.broadcastToTablets("MENU_UPDATED");
            posServer.log("[ADMIN] " + message);
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
        lblImageHint = new javax.swing.JLabel();
        splitCombo = new javax.swing.JSplitPane();
        pnlList = new javax.swing.JPanel();
        scrollCombos = new javax.swing.JScrollPane();
        tblCombos = new javax.swing.JTable();
        pnlEdit = new javax.swing.JPanel();
        pnlTop = new javax.swing.JPanel();
        pnlFields = new javax.swing.JPanel();
        lblComboId = new javax.swing.JLabel();
        txtComboId = new javax.swing.JTextField();
        lblComboName = new javax.swing.JLabel();
        txtComboName = new javax.swing.JTextField();
        lblComboPrice = new javax.swing.JLabel();
        txtComboPrice = new javax.swing.JTextField();
        lblComboStatus = new javax.swing.JLabel();
        cboComboStatus = new javax.swing.JComboBox<>();
        lblComboDesc = new javax.swing.JLabel();
        scrollComboDesc = new javax.swing.JScrollPane();
        txtComboDesc = new javax.swing.JTextArea();
        pnlImageBox = new javax.swing.JPanel();
        lblImageTitle = new javax.swing.JLabel();
        lblImagePreview = new javax.swing.JLabel();
        pnlImageButtons = new javax.swing.JPanel();
        btnChooseImage = new javax.swing.JButton();
        btnRemoveImage = new javax.swing.JButton();
        pnlItems = new javax.swing.JPanel();
        scrollComboItems = new javax.swing.JScrollPane();
        tblComboItems = new javax.swing.JTable();
        pnlItemTools = new javax.swing.JPanel();
        cboMenuItem = new javax.swing.JComboBox<>();
        pnlItemButtons = new javax.swing.JPanel();
        spnQuantity = new javax.swing.JSpinner();
        btnAddItem = new javax.swing.JButton();
        btnRemoveItem = new javax.swing.JButton();
        pnlBottom = new javax.swing.JPanel();
        lblComboValue = new javax.swing.JLabel();
        pnlEditButtons = new javax.swing.JPanel();
        btnAdd = new javax.swing.JButton();
        btnUpdate = new javax.swing.JButton();
        btnDelete = new javax.swing.JButton();
        btnClear = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        setTitle("Quản lý combo");

        pnlHeader.setBackground(new java.awt.Color(31, 36, 48));
        pnlHeader.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 16, 12, 16));
        pnlHeader.setLayout(new java.awt.BorderLayout(12, 0));

        lblTitle.setFont(new java.awt.Font("Segoe UI", 1, 17)); // NOI18N
        lblTitle.setForeground(new java.awt.Color(241, 196, 15));
        lblTitle.setText("QUẢN LÝ COMBO ƯU ĐÃI");
        pnlHeader.add(lblTitle, java.awt.BorderLayout.WEST);

        lblImageHint.setForeground(new java.awt.Color(200, 206, 216));
        lblImageHint.setText("Ảnh JPG / PNG được tự thu nhỏ để gửi nhanh tới Tablet");
        pnlHeader.add(lblImageHint, java.awt.BorderLayout.EAST);

        getContentPane().add(pnlHeader, java.awt.BorderLayout.NORTH);

        splitCombo.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10));
        splitCombo.setDividerLocation(430);
        splitCombo.setResizeWeight(0.4);

        pnlList.setBackground(new java.awt.Color(255, 255, 255));
        pnlList.setBorder(javax.swing.BorderFactory.createTitledBorder(null, "Danh sách combo", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Segoe UI", 1, 12), new java.awt.Color(44, 62, 80))); // NOI18N
        pnlList.setLayout(new java.awt.BorderLayout());

        tblCombos.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        tblCombos.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {

            },
            new String [] {
                "Mã", "Tên combo", "Giá", "Trạng thái", "Số món", "Ảnh"
            }
        ) {
            boolean[] canEdit = new boolean [] {
                false, false, false, false, false, false
            };

            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return canEdit [columnIndex];
            }
        });
        tblCombos.setRowHeight(28);
        scrollCombos.setViewportView(tblCombos);

        pnlList.add(scrollCombos, java.awt.BorderLayout.CENTER);

        splitCombo.setLeftComponent(pnlList);

        pnlEdit.setBackground(new java.awt.Color(255, 255, 255));
        pnlEdit.setBorder(javax.swing.BorderFactory.createTitledBorder(null, "Chi tiết combo", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Segoe UI", 1, 12), new java.awt.Color(44, 62, 80))); // NOI18N
        pnlEdit.setLayout(new java.awt.BorderLayout(0, 8));

        pnlTop.setOpaque(false);
        pnlTop.setLayout(new java.awt.BorderLayout(14, 0));

        pnlFields.setOpaque(false);
        pnlFields.setLayout(new java.awt.GridBagLayout());

        lblComboId.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblComboId.setForeground(new java.awt.Color(44, 62, 80));
        lblComboId.setText("Mã combo");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(5, 4, 5, 8);
        pnlFields.add(lblComboId, gridBagConstraints);

        txtComboId.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        txtComboId.setEditable(false);
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(5, 0, 5, 4);
        pnlFields.add(txtComboId, gridBagConstraints);

        lblComboName.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblComboName.setForeground(new java.awt.Color(44, 62, 80));
        lblComboName.setText("Tên combo");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(5, 4, 5, 8);
        pnlFields.add(lblComboName, gridBagConstraints);

        txtComboName.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(5, 0, 5, 4);
        pnlFields.add(txtComboName, gridBagConstraints);

        lblComboPrice.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblComboPrice.setForeground(new java.awt.Color(44, 62, 80));
        lblComboPrice.setText("Giá combo (đ)");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 2;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(5, 4, 5, 8);
        pnlFields.add(lblComboPrice, gridBagConstraints);

        txtComboPrice.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(5, 0, 5, 4);
        pnlFields.add(txtComboPrice, gridBagConstraints);

        lblComboStatus.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblComboStatus.setForeground(new java.awt.Color(44, 62, 80));
        lblComboStatus.setText("Trạng thái");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 3;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(5, 4, 5, 8);
        pnlFields.add(lblComboStatus, gridBagConstraints);

        cboComboStatus.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        cboComboStatus.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "Đang bán", "Tạm ngừng" }));
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 3;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(5, 0, 5, 4);
        pnlFields.add(cboComboStatus, gridBagConstraints);

        lblComboDesc.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        lblComboDesc.setForeground(new java.awt.Color(44, 62, 80));
        lblComboDesc.setText("Mô tả");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 4;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        gridBagConstraints.insets = new java.awt.Insets(5, 4, 5, 8);
        pnlFields.add(lblComboDesc, gridBagConstraints);

        txtComboDesc.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        txtComboDesc.setColumns(20);
        txtComboDesc.setLineWrap(true);
        txtComboDesc.setRows(3);
        txtComboDesc.setWrapStyleWord(true);
        scrollComboDesc.setViewportView(txtComboDesc);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 4;
        gridBagConstraints.fill = java.awt.GridBagConstraints.BOTH;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 1.0;
        gridBagConstraints.insets = new java.awt.Insets(5, 0, 5, 4);
        pnlFields.add(scrollComboDesc, gridBagConstraints);

        pnlTop.add(pnlFields, java.awt.BorderLayout.CENTER);

        pnlImageBox.setOpaque(false);
        pnlImageBox.setLayout(new java.awt.BorderLayout(0, 6));

        lblImageTitle.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        lblImageTitle.setForeground(new java.awt.Color(44, 62, 80));
        lblImageTitle.setText("Ảnh combo (hiện trên Tablet)");
        pnlImageBox.add(lblImageTitle, java.awt.BorderLayout.NORTH);

        lblImagePreview.setBackground(new java.awt.Color(236, 240, 241));
        lblImagePreview.setForeground(new java.awt.Color(127, 140, 141));
        lblImagePreview.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        lblImagePreview.setText("Chưa có ảnh");
        lblImagePreview.setToolTipText("Bấm đúp để chọn ảnh");
        lblImagePreview.setBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(222, 226, 232)));
        lblImagePreview.setOpaque(true);
        lblImagePreview.setPreferredSize(new java.awt.Dimension(200, 150));
        pnlImageBox.add(lblImagePreview, java.awt.BorderLayout.CENTER);

        pnlImageButtons.setOpaque(false);
        pnlImageButtons.setLayout(new java.awt.GridLayout(1, 2, 6, 0));

        btnChooseImage.setBackground(new java.awt.Color(41, 128, 185));
        btnChooseImage.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnChooseImage.setForeground(new java.awt.Color(255, 255, 255));
        btnChooseImage.setText("Chọn ảnh...");
        btnChooseImage.setFocusable(false);
        btnChooseImage.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnChooseImageActionPerformed(evt);
            }
        });
        pnlImageButtons.add(btnChooseImage);

        btnRemoveImage.setBackground(new java.awt.Color(231, 76, 60));
        btnRemoveImage.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnRemoveImage.setForeground(new java.awt.Color(255, 255, 255));
        btnRemoveImage.setText("Xóa ảnh");
        btnRemoveImage.setFocusable(false);
        btnRemoveImage.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnRemoveImageActionPerformed(evt);
            }
        });
        pnlImageButtons.add(btnRemoveImage);

        pnlImageBox.add(pnlImageButtons, java.awt.BorderLayout.SOUTH);

        pnlTop.add(pnlImageBox, java.awt.BorderLayout.EAST);

        pnlEdit.add(pnlTop, java.awt.BorderLayout.NORTH);

        pnlItems.setBorder(javax.swing.BorderFactory.createTitledBorder(null, "Các món trong combo", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION, javax.swing.border.TitledBorder.DEFAULT_POSITION, new java.awt.Font("Segoe UI", 1, 12), new java.awt.Color(44, 62, 80))); // NOI18N
        pnlItems.setOpaque(false);
        pnlItems.setLayout(new java.awt.BorderLayout(0, 6));

        tblComboItems.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        tblComboItems.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {

            },
            new String [] {
                "Mã món", "Tên món", "SL"
            }
        ) {
            boolean[] canEdit = new boolean [] {
                false, false, false
            };

            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return canEdit [columnIndex];
            }
        });
        tblComboItems.setRowHeight(28);
        scrollComboItems.setViewportView(tblComboItems);

        pnlItems.add(scrollComboItems, java.awt.BorderLayout.CENTER);

        pnlItemTools.setOpaque(false);
        pnlItemTools.setLayout(new java.awt.BorderLayout(6, 0));

        cboMenuItem.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        pnlItemTools.add(cboMenuItem, java.awt.BorderLayout.CENTER);

        pnlItemButtons.setOpaque(false);
        pnlItemButtons.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));

        spnQuantity.setFont(new java.awt.Font("Segoe UI", 0, 13)); // NOI18N
        spnQuantity.setModel(new javax.swing.SpinnerNumberModel(1, 1, 20, 1));
        pnlItemButtons.add(spnQuantity);

        btnAddItem.setBackground(new java.awt.Color(41, 128, 185));
        btnAddItem.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnAddItem.setForeground(new java.awt.Color(255, 255, 255));
        btnAddItem.setText("Thêm món");
        btnAddItem.setFocusable(false);
        btnAddItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnAddItemActionPerformed(evt);
            }
        });
        pnlItemButtons.add(btnAddItem);

        btnRemoveItem.setBackground(new java.awt.Color(236, 240, 241));
        btnRemoveItem.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnRemoveItem.setForeground(new java.awt.Color(44, 62, 80));
        btnRemoveItem.setText("Bỏ món");
        btnRemoveItem.setFocusable(false);
        btnRemoveItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnRemoveItemActionPerformed(evt);
            }
        });
        pnlItemButtons.add(btnRemoveItem);

        pnlItemTools.add(pnlItemButtons, java.awt.BorderLayout.EAST);

        pnlItems.add(pnlItemTools, java.awt.BorderLayout.SOUTH);

        pnlEdit.add(pnlItems, java.awt.BorderLayout.CENTER);

        pnlBottom.setOpaque(false);
        pnlBottom.setLayout(new java.awt.BorderLayout(0, 6));

        lblComboValue.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        lblComboValue.setForeground(new java.awt.Color(39, 174, 96));
        lblComboValue.setText(" ");
        pnlBottom.add(lblComboValue, java.awt.BorderLayout.NORTH);

        pnlEditButtons.setOpaque(false);
        pnlEditButtons.setLayout(new java.awt.GridLayout(1, 4, 8, 0));

        btnAdd.setBackground(new java.awt.Color(39, 174, 96));
        btnAdd.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnAdd.setForeground(new java.awt.Color(255, 255, 255));
        btnAdd.setText("Thêm combo");
        btnAdd.setFocusable(false);
        btnAdd.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnAddActionPerformed(evt);
            }
        });
        pnlEditButtons.add(btnAdd);

        btnUpdate.setBackground(new java.awt.Color(41, 128, 185));
        btnUpdate.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnUpdate.setForeground(new java.awt.Color(255, 255, 255));
        btnUpdate.setText("Lưu");
        btnUpdate.setFocusable(false);
        btnUpdate.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnUpdateActionPerformed(evt);
            }
        });
        pnlEditButtons.add(btnUpdate);

        btnDelete.setBackground(new java.awt.Color(231, 76, 60));
        btnDelete.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnDelete.setForeground(new java.awt.Color(255, 255, 255));
        btnDelete.setText("Xóa combo");
        btnDelete.setFocusable(false);
        btnDelete.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnDeleteActionPerformed(evt);
            }
        });
        pnlEditButtons.add(btnDelete);

        btnClear.setBackground(new java.awt.Color(236, 240, 241));
        btnClear.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnClear.setForeground(new java.awt.Color(44, 62, 80));
        btnClear.setText("Nhập mới");
        btnClear.setFocusable(false);
        btnClear.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnClearActionPerformed(evt);
            }
        });
        pnlEditButtons.add(btnClear);

        pnlBottom.add(pnlEditButtons, java.awt.BorderLayout.CENTER);

        pnlEdit.add(pnlBottom, java.awt.BorderLayout.SOUTH);

        splitCombo.setRightComponent(pnlEdit);

        getContentPane().add(splitCombo, java.awt.BorderLayout.CENTER);

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void btnChooseImageActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnChooseImageActionPerformed
        chooseImage();
    }//GEN-LAST:event_btnChooseImageActionPerformed

    private void btnRemoveImageActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnRemoveImageActionPerformed
        showImage(null);
        lblComboValue.setText("Đã xóa ảnh, bấm \"Lưu\" để cập nhật.");
    }//GEN-LAST:event_btnRemoveImageActionPerformed

    private void btnAddItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnAddItemActionPerformed
        int i = cboMenuItem.getSelectedIndex();
        if (i < 0) return;
        MenuItem m = menu.get(i);
        int qty = (Integer) spnQuantity.getValue();
        for (int r = 0; r < itemModel.getRowCount(); r++) {
            if ((Integer) itemModel.getValueAt(r, 0) == m.getItemId()) {
                itemModel.setValueAt((Integer) itemModel.getValueAt(r, 2) + qty, r, 2);
                updateValue();
                return;
            }
        }
        itemModel.addRow(new Object[]{m.getItemId(), m.getItemName(), qty});
        updateValue();
    }//GEN-LAST:event_btnAddItemActionPerformed

    private void btnRemoveItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnRemoveItemActionPerformed
        int r = tblComboItems.getSelectedRow();
        if (r >= 0) {
            itemModel.removeRow(r);
            updateValue();
        }
    }//GEN-LAST:event_btnRemoveItemActionPerformed

    private void btnAddActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnAddActionPerformed
        Combo c = readForm(0);
        if (c == null) return;
        if (ComboDao.addCombo(c) > 0) afterChange("Thêm combo " + c.getComboName());
        else JOptionPane.showMessageDialog(this, "Không thêm được combo.", "Lỗi", JOptionPane.ERROR_MESSAGE);
    }//GEN-LAST:event_btnAddActionPerformed

    private void btnUpdateActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnUpdateActionPerformed
        Combo c = readForm(Integer.parseInt(txtComboId.getText()));
        if (c == null) return;
        if (ComboDao.updateCombo(c)) afterChange("Sửa combo " + c.getComboName());
        else JOptionPane.showMessageDialog(this, "Không lưu được combo.", "Lỗi", JOptionPane.ERROR_MESSAGE);
    }//GEN-LAST:event_btnUpdateActionPerformed

    private void btnDeleteActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnDeleteActionPerformed
        int id = Integer.parseInt(txtComboId.getText());
        if (JOptionPane.showConfirmDialog(this, "Xóa combo " + txtComboName.getText() + "?\nCác đơn cũ vẫn giữ tên combo.", "Xóa combo",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        if (ComboDao.deleteCombo(id)) afterChange("Xóa combo #" + id);
    }//GEN-LAST:event_btnDeleteActionPerformed

    private void btnClearActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnClearActionPerformed
        clearForm();
    }//GEN-LAST:event_btnClearActionPerformed

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnAdd;
    private javax.swing.JButton btnAddItem;
    private javax.swing.JButton btnChooseImage;
    private javax.swing.JButton btnClear;
    private javax.swing.JButton btnDelete;
    private javax.swing.JButton btnRemoveImage;
    private javax.swing.JButton btnRemoveItem;
    private javax.swing.JButton btnUpdate;
    private javax.swing.JComboBox<String> cboComboStatus;
    private javax.swing.JComboBox<String> cboMenuItem;
    private javax.swing.JLabel lblComboDesc;
    private javax.swing.JLabel lblComboId;
    private javax.swing.JLabel lblComboName;
    private javax.swing.JLabel lblComboPrice;
    private javax.swing.JLabel lblComboStatus;
    private javax.swing.JLabel lblComboValue;
    private javax.swing.JLabel lblImageHint;
    private javax.swing.JLabel lblImagePreview;
    private javax.swing.JLabel lblImageTitle;
    private javax.swing.JLabel lblTitle;
    private javax.swing.JPanel pnlBottom;
    private javax.swing.JPanel pnlEdit;
    private javax.swing.JPanel pnlEditButtons;
    private javax.swing.JPanel pnlFields;
    private javax.swing.JPanel pnlHeader;
    private javax.swing.JPanel pnlImageBox;
    private javax.swing.JPanel pnlImageButtons;
    private javax.swing.JPanel pnlItemButtons;
    private javax.swing.JPanel pnlItemTools;
    private javax.swing.JPanel pnlItems;
    private javax.swing.JPanel pnlList;
    private javax.swing.JPanel pnlTop;
    private javax.swing.JScrollPane scrollComboDesc;
    private javax.swing.JScrollPane scrollComboItems;
    private javax.swing.JScrollPane scrollCombos;
    private javax.swing.JSplitPane splitCombo;
    private javax.swing.JSpinner spnQuantity;
    private javax.swing.JTable tblComboItems;
    private javax.swing.JTable tblCombos;
    private javax.swing.JTextArea txtComboDesc;
    private javax.swing.JTextField txtComboId;
    private javax.swing.JTextField txtComboName;
    private javax.swing.JTextField txtComboPrice;
    // End of variables declaration//GEN-END:variables
}
