package com.haidilao.pos.model;

/**
 * Model thiết bị mạng tương thích sơ đồ Cisco Packet Tracer
 */
public class NetworkDevice {
    private String deviceId;
    private String vlanName;
    private String ipAddress;
    private String macAddress;
    private String securityStatus;

    public NetworkDevice() {
    }

    public NetworkDevice(String deviceId, String vlanName, String ipAddress, String macAddress, String securityStatus) {
        this.deviceId = deviceId;
        this.vlanName = vlanName;
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

    public String getVlanName() {
        return vlanName;
    }

    public void setVlanName(String vlanName) {
        this.vlanName = vlanName;
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
}
