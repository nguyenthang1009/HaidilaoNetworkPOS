package com.haidilao.pos.protocol;

/**
 * Các hằng số giao thức mạng và thông số bảo mật hệ thống
 */
public final class ProtocolConstants {
    private ProtocolConstants() {}

    // Cổng dịch vụ mạng TCP
    public static final int SERVER_PORT = 8888;
    public static final String DEFAULT_SERVER_HOST = "127.0.0.1";

    // Tự tìm máy chủ trong mạng Wi-Fi: máy khách gửi UDP broadcast tới cổng 8889, máy chủ trả lời kèm cổng TCP
    public static final int DISCOVERY_PORT = 8889;
    public static final String DISCOVERY_REQUEST = "LTO_DISCOVER|1";
    public static final String DISCOVERY_REPLY = "LTO_SERVER|1";
    public static final int DISCOVERY_TIMEOUT_MS = 1500;

    // Heartbeat: client gửi PING mỗi 5 giây, server trả PONG.
    // Bên nào quá 15 giây không nhận được gì từ bên kia thì coi như mất kết nối (máy rớt Wi-Fi, rút dây mạng...)
    public static final int HEARTBEAT_INTERVAL_MS = 5000;
    public static final int HEARTBEAT_TIMEOUT_MS = 15000;

    // Số tin nhắn gần nhất gửi lại khi client vừa kết nối (lịch sử trò chuyện)
    public static final int CHAT_HISTORY_LIMIT = 30;

    // Khóa thiết bị: máy nhân viên (Lễ tân, Bếp) dùng khóa riêng, Tablet của khách dùng khóa khác.
    // Máy nhân viên còn phải đăng nhập bằng tài khoản nhân viên.
    public static final String KEY_STAFF = "ADMIN_KEY_2026";
    public static final String KEY_RECEPTION = KEY_STAFF;
    public static final String KEY_KITCHEN = KEY_STAFF;
    public static final String KEY_WAITER = KEY_STAFF;
    public static final String KEY_TABLET = "RES_KEY_2026";

    // Giới hạn chống gửi dữ liệu bất thường
    public static final int MAX_ORDER_LINES = 50;      // tối đa số dòng trong 1 đơn
    public static final int MAX_LINE_QUANTITY = 50;    // tối đa số lượng 1 món
    public static final int MAX_NOTE_LENGTH = 200;     // độ dài ghi chú tối đa
    public static final int MAX_LOGIN_FAILS = 5;       // sai mật khẩu quá 5 lần trong 1 phút thì tạm chặn IP đó 1 phút

    // Đặt bàn: mỗi lượt giữ bàn 2 giờ; thẻ bàn hiện "ĐÃ ĐẶT" khi còn dưới 60 phút tới giờ hẹn;
    // quá giờ hẹn 30 phút chưa đến thì coi như có thể "KHÔNG ĐẾN"
    public static final int RESERVATION_MINUTES = 120;
    public static final int RESERVATION_HOLD_MINUTES = 60;
    public static final int RESERVATION_LATE_MINUTES = 30;

    // Ký tự phân cách giao thức
    public static final String DELIMITER = "|";
    public static final String DELIMITER_REGEX = "\\|";
}
