package com.haidilao.pos.model;

/**
 * Thiết bị đã từng kết nối vào Server (Tablet, Lễ tân, Bếp): IP thật, MAC và trạng thái khóa
 */
public class NetworkDevice {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_LOCKED = "LOCKED";

    private String deviceId;
    private String deviceRole;
    private String ipAddress;
    private String macAddress;
    private String securityStatus;

    public NetworkDevice() {
    }

    public NetworkDevice(String deviceId, String deviceRole, String ipAddress, String macAddress, String securityStatus) {
        this.deviceId = deviceId;
        this.deviceRole = deviceRole;
        this.ipAddress = ipAddress;
        this.macAddress = macAddress;
        this.securityStatus = securityStatus;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getDeviceRole() {
        return deviceRole;
    }

    public void setDeviceRole(String deviceRole) {
        this.deviceRole = deviceRole;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getMacAddress() {
        return macAddress;
    }

    public void setMacAddress(String macAddress) {
        this.macAddress = macAddress;
    }

    public String getSecurityStatus() {
        return securityStatus;
    }

    public void setSecurityStatus(String securityStatus) {
        this.securityStatus = securityStatus;
    }

    public boolean isLocked() {
        return STATUS_LOCKED.equalsIgnoreCase(securityStatus);
    }
}
