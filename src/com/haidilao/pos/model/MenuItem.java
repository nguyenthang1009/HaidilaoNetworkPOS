package com.haidilao.pos.model;

import java.text.DecimalFormat;

/**
 * Model biểu diễn món ăn trong thực đơn Haidilao
 */
public class MenuItem {
    private int itemId;
    private String category;
    private String itemName;
    private long price;
    private String status;
    private byte[] imageData; // ảnh món ăn (JPEG đã thu nhỏ), null nếu chưa có ảnh

    public MenuItem() {
    }

    public MenuItem(int itemId, String category, String itemName, long price, String status) {
        this.itemId = itemId;
        this.category = category;
        this.itemName = itemName;
        this.price = price;
        this.status = status;
    }

    public int getItemId() {
        return itemId;
    }

    public void setItemId(int itemId) {
        this.itemId = itemId;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getItemName() {
        return itemName;
    }

    public void setItemName(String itemName) {
        this.itemName = itemName;
    }

    public long getPrice() {
        return price;
    }

    public void setPrice(long price) {
        this.price = price;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public byte[] getImageData() {
        return imageData;
    }

    public void setImageData(byte[] imageData) {
        this.imageData = imageData;
    }

    public String getFormattedPrice() {
        return new DecimalFormat("#,### VNĐ").format(price);
    }

    @Override
    public String toString() {
        return itemName + " - " + getFormattedPrice();
    }
}
