package com.haidilao.pos.model;

/**
 * Bàn ăn trong nhà hàng. table_id trùng với tên thiết bị Tablet đặt tại bàn (vd. Tablet_Ban1)
 */
public class DiningTable {
    public static final String STATUS_EMPTY = "TRỐNG";
    public static final String STATUS_SERVING = "ĐANG PHỤC VỤ";
    public static final String STATUS_RESERVED = "ĐÃ ĐẶT";
    public static final String STATUS_CLEANING = "CẦN DỌN";

    private String tableId;
    private String tableName;
    private int capacity;
    private String area;
    private String status;
    private int sortOrder;

    public DiningTable(String tableId, String tableName, int capacity, String area, String status, int sortOrder) {
        this.tableId = tableId;
        this.tableName = tableName;
        this.capacity = capacity;
        this.area = area;
        this.status = status;
        this.sortOrder = sortOrder;
    }

    public String getTableId() {
        return tableId;
    }

    public String getTableName() {
        return tableName;
    }

    public int getCapacity() {
        return capacity;
    }

    public String getArea() {
        return area;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
