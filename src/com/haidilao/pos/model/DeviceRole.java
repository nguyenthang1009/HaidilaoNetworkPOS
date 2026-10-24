package com.haidilao.pos.model;

/**
 * Vai trò của thiết bị khi kết nối vào Server
 */
public enum DeviceRole {
    ADMIN("Máy chủ / Quản trị"),
    RECEPTION("Quầy Lễ tân"),
    TABLET("Tablet tại bàn"),
    KITCHEN("Màn hình Bếp"),
    WAITER("Nhân viên phục vụ"),
    UNKNOWN("Không xác định");

    private final String description;

    DeviceRole(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
