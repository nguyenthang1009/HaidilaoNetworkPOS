package com.haidilao.pos.model;

/**
 * Một dòng thống kê: nhãn (ngày / tên món / bàn / nhân viên), số lượng và số tiền
 */
public class StatRow {
    private final String label;
    private long count;
    private long amount;

    public StatRow(String label, long count, long amount) {
        this.label = label;
        this.count = count;
        this.amount = amount;
    }

    public String getLabel() {
        return label;
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }

    public long getAmount() {
        return amount;
    }

    public void setAmount(long amount) {
        this.amount = amount;
    }
}
