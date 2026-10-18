package com.haidilao.pos.client;

/**
 * Interface nhận phản hồi và dữ liệu từ Server đẩy về phía Client (Tablet hoặc Lễ Tân)
 */
public interface ClientEventListener {
    void onConnected();
    void onDisconnected(String reason);
    void onMessageReceived(String rawMessage);
    void onKicked(String reason);
    void onAuthFailed(String reason); // Server từ chối đăng nhập (sai khóa, thiết bị đang bị khóa cổng...)
}
