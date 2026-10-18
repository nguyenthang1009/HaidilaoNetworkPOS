package com.haidilao.pos.model;

import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;

/**
 * Model đơn đặt món ăn
 */
public class Order {
    private int orderId;
    private String tableId;
    private String itemsSummary;
    private long totalAmount;
    private String orderStatus;
    private Timestamp createdAt;

    public Order() {
    }

    public Order(int orderId, String tableId, String itemsSummary, long totalAmount, String orderStatus, Timestamp createdAt) {
        this.orderId = orderId;
        this.tableId = tableId;
        this.itemsSummary = itemsSummary;
        this.totalAmount = totalAmount;
        this.orderStatus = orderStatus;
        this.createdAt = createdAt;
    }

    public int getOrderId() {
        return orderId;
    }

    public void setOrderId(int orderId) {
        this.orderId = orderId;
    }

    public String getTableId() {
        return tableId;
    }

    public void setTableId(String tableId) {
        this.tableId = tableId;
    }

    public String getItemsSummary() {
        return itemsSummary;
    }

    public void setItemsSummary(String itemsSummary) {
        this.itemsSummary = itemsSummary;
    }

    public long getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(long totalAmount) {
        this.totalAmount = totalAmount;
    }

    public String getOrderStatus() {
        return orderStatus;
    }

    public void setOrderStatus(String orderStatus) {
        this.orderStatus = orderStatus;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public String getFormattedTotal() {
        return new DecimalFormat("#,### VNĐ").format(totalAmount);
    }

    public String getFormattedTime() {
        if (createdAt == null) return "";
        return new SimpleDateFormat("HH:mm:ss dd/MM").format(createdAt);
    }
}
