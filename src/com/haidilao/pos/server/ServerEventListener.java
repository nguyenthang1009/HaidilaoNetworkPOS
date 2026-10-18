package com.haidilao.pos.server;

import com.haidilao.pos.model.NetworkDevice;

/**
 * Interface lắng nghe sự kiện từ POS Server đẩy lên giao diện ServerAdminFrame
 */
public interface ServerEventListener {
    void onLog(String message);
    void onServerStarted(int port);
    void onServerStopped();
    void onDeviceConnected(String deviceId, String vlan, String ip, String mac, String status);
    void onDeviceDisconnected(String deviceId);
    void onRevenueUpdated(long totalRevenue);
}
