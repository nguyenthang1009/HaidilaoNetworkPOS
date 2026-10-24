package com.haidilao.pos.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Combo món ăn: một nhóm món bán chung với giá ưu đãi
 */
public class Combo {
    private int comboId;
    private String comboName;
    private long price;
    private String description;
    private String status;
    private byte[] imageData; // ảnh combo (JPEG đã thu nhỏ), null nếu chưa có
    private final List<ComboItem> items = new ArrayList<>();

    public Combo(int comboId, String comboName, long price, String description, String status) {
        this.comboId = comboId;
        this.comboName = comboName;
        this.price = price;
        this.description = description;
        this.status = status;
    }

    public int getComboId() {
        return comboId;
    }

    public String getComboName() {
        return comboName;
    }

    public long getPrice() {
        return price;
    }

    public String getDescription() {
        return description;
    }

    public String getStatus() {
        return status;
    }

    public List<ComboItem> getItems() {
        return items;
    }

    public byte[] getImageData() {
        return imageData;
    }

    public void setImageData(byte[] imageData) {
        this.imageData = imageData;
    }

    /** Tóm tắt các món trong combo, vd. "Lẩu cà chua x1, Bò Mỹ x2" */
    public String getItemsSummary() {
        StringBuilder sb = new StringBuilder();
        for (ComboItem ci : items) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(ci.getItemName()).append(" x").append(ci.getQuantity());
        }
        return sb.toString();
    }

    /**
     * Một món trong combo
     */
    public static class ComboItem {
        private final int itemId;
        private final String itemName;
        private final int quantity;

        public ComboItem(int itemId, String itemName, int quantity) {
            this.itemId = itemId;
            this.itemName = itemName;
            this.quantity = quantity;
        }

        public int getItemId() {
            return itemId;
        }

        public String getItemName() {
            return itemName;
        }

        public int getQuantity() {
            return quantity;
        }
    }
}
