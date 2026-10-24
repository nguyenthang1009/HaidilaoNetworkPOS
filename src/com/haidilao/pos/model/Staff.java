package com.haidilao.pos.model;

/**
 * Nhân viên nhà hàng. Vai trò quyết định được dùng chương trình nào:
 * QUẢN LÝ (máy chủ, lễ tân, bếp) · THU NGÂN (lễ tân) · BẾP (màn hình bếp) · PHỤC VỤ (được gán vào bàn)
 */
public class Staff {
    public static final String ROLE_MANAGER = "QUẢN LÝ";
    public static final String ROLE_CASHIER = "THU NGÂN";
    public static final String ROLE_WAITER = "PHỤC VỤ";
    public static final String ROLE_KITCHEN = "BẾP";

    private int staffId;
    private String username;
    private String fullName;
    private String role;
    private String phone;
    private boolean active;

    public Staff(int staffId, String username, String fullName, String role, String phone, boolean active) {
        this.staffId = staffId;
        this.username = username;
        this.fullName = fullName;
        this.role = role;
        this.phone = phone;
        this.active = active;
    }

    public int getStaffId() {
        return staffId;
    }

    public String getUsername() {
        return username;
    }

    public String getFullName() {
        return fullName;
    }

    public String getRole() {
        return role;
    }

    public String getPhone() {
        return phone;
    }

    public boolean isActive() {
        return active;
    }

    /** Vai trò này có được đăng nhập chương trình Lễ tân không */
    public boolean canUseReception() {
        return ROLE_MANAGER.equals(role) || ROLE_CASHIER.equals(role);
    }

    /** Vai trò này có được đăng nhập chương trình Nhân viên phục vụ không */
    public boolean canUseWaiter() {
        return ROLE_MANAGER.equals(role) || ROLE_WAITER.equals(role);
    }

    /** Vai trò này có được đăng nhập Màn hình Bếp không */
    public boolean canUseKitchen() {
        return ROLE_MANAGER.equals(role) || ROLE_KITCHEN.equals(role);
    }

    @Override
    public String toString() {
        return fullName;
    }
}
