package com.haidilao.pos.model;

/**
 * Phân quyền vai trò thiết bị trong mô hình mạng POS
 */
public enum DeviceRole {
    ADMIN("Quản Trị Mạng & Server"),
    RECEPTION("Quầy Lễ Tân (VLAN 10)"),
    TABLET("Tablet Khách Hàng (VLAN 20)"),
    UNKNOWN("Không Xác Định");

    private final String description;

    DeviceRole(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
