package com.haidilao.pos.model;

/**
 * Một dòng trong đơn gọi món: một món lẻ hoặc một combo, kèm số lượng và ghi chú cho bếp
 */
public class OrderLine {
    public static final char TYPE_ITEM = 'M';   // món lẻ trong thực đơn
    public static final char TYPE_COMBO = 'C';  // combo

    private final char type;
    private final int refId;        // item_id hoặc combo_id
    private String name;            // tên món / combo tại thời điểm đặt
    private final int quantity;
    private long unitPrice;         // giá do Server lấy từ database (không tin giá client gửi)
    private final String note;

    public OrderLine(char type, int refId, String name, int quantity, long unitPrice, String note) {
        this.type = type;
        this.refId = refId;
        this.name = name;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.note = note == null ? "" : note;
    }

    public char getType() {
        return type;
    }

    public boolean isCombo() {
        return type == TYPE_COMBO;
    }

    public int getRefId() {
        return refId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getQuantity() {
        return quantity;
    }

    public long getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(long unitPrice) {
        this.unitPrice = unitPrice;
    }

    public long getAmount() {
        return unitPrice * quantity;
    }

    public String getNote() {
        return note;
    }
}
