package com.haidilao.pos.model;

import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Model đơn đặt món ăn
 */
public class Order {
    // Vòng đời của một đơn: Tablet gửi → Bếp nhận → nấu → xong → nhân viên mang ra (hoặc hủy)
    public static final String STATUS_WAITING = "CHỜ BẾP";
    public static final String STATUS_COOKING = "ĐANG NẤU";
    public static final String STATUS_DONE = "ĐÃ XONG";
    public static final String STATUS_SERVED = "ĐÃ PHỤC VỤ";
    public static final String STATUS_CANCELLED = "ĐÃ HỦY";

    private int orderId;
    private int orderNo;            // số thứ tự trong ngày (#001, #002...)
    private int sessionId;
    private String tableId;
    private String tableName;
    private String itemsSummary;
    private long totalAmount;
    private String orderStatus;
    private String note;            // ghi chú chung cho bếp
    private Timestamp createdAt;
    private final List<OrderLine> lines = new ArrayList<>();

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

    public int getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(int orderNo) {
        this.orderNo = orderNo;
    }

    /** Số thứ tự dạng #005 */
    public String getOrderNoText() {
        return formatOrderNo(orderNo);
    }

    public static String formatOrderNo(int orderNo) {
        return String.format("#%03d", orderNo);
    }

    public int getSessionId() {
        return sessionId;
    }

    public void setSessionId(int sessionId) {
        this.sessionId = sessionId;
    }

    public String getTableId() {
        return tableId;
    }

    public void setTableId(String tableId) {
        this.tableId = tableId;
    }

    public String getTableName() {
        return tableName == null ? tableId : tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
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

    public String getNote() {
        return note == null ? "" : note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public List<OrderLine> getLines() {
        return lines;
    }

    public String getFormattedTotal() {
        return new DecimalFormat("#,### VNĐ").format(totalAmount);
    }

    public String getFormattedTime() {
        if (createdAt == null) return "";
        return new SimpleDateFormat("HH:mm:ss dd/MM").format(createdAt);
    }

    public String getShortTime() {
        if (createdAt == null) return "";
        return new SimpleDateFormat("HH:mm").format(createdAt);
    }
}
