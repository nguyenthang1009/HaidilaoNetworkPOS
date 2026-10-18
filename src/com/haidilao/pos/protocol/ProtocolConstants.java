package com.haidilao.pos.protocol;

/**
 * Các hằng số giao thức mạng và thông số bảo mật hệ thống
 */
public final class ProtocolConstants {
    private ProtocolConstants() {}

    // Cổng dịch vụ mạng TCP
    public static final int SERVER_PORT = 8888;
    public static final String DEFAULT_SERVER_HOST = "127.0.0.1";

    // Heartbeat: client gửi PING mỗi 5 giây, server trả PONG.
    // Bên nào quá 15 giây không nhận được gì từ bên kia thì coi như mất kết nối (máy rớt Wi-Fi, rút dây mạng...)
    public static final int HEARTBEAT_INTERVAL_MS = 5000;
    public static final int HEARTBEAT_TIMEOUT_MS = 15000;

    // Số tin nhắn gần nhất gửi lại khi client vừa kết nối (lịch sử trò chuyện)
    public static final int CHAT_HISTORY_LIMIT = 30;

    // Khóa mật mã bảo mật (Shared Secret Keys per VLAN)
    public static final String KEY_ADMIN = "ADMIN_KEY_2026";
    public static final String KEY_RECEPTION = "ADMIN_KEY_2026";
    public static final String KEY_TABLET = "RES_KEY_2026";

    // Phân vùng mạng (VLANs & Subnets theo sơ đồ Cisco Packet Tracer)
    public static final String VLAN_ADMIN = "VLAN 10 (Quản trị)";
    public static final String VLAN_GUEST = "VLAN 20 (Khách Wi-Fi)";
    public static final String SUBNET_ADMIN = "192.168.10.0/24";
    public static final String SUBNET_GUEST = "192.168.20.0/24";
    public static final String SERVER_IP = "192.168.10.50";
    public static final String RECEPTION_IP = "192.168.10.20";
    public static final String ADMIN_IP = "192.168.10.30";

    // Ký tự phân cách giao thức
    public static final String DELIMITER = "|";
    public static final String DELIMITER_REGEX = "\\|";
}
