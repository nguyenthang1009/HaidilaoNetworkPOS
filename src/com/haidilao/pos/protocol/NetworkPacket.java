package com.haidilao.pos.protocol;

import java.util.Base64;

/**
 * Lớp đóng gói và giải mã gói tin tầng ứng dụng qua TCP Socket
 */
public class NetworkPacket {
    private CommandType type;
    private String[] rawParts;

    public NetworkPacket(CommandType type, String[] rawParts) {
        this.type = type;
        this.rawParts = rawParts;
    }

    public CommandType getType() {
        return type;
    }

    public String[] getRawParts() {
        return rawParts;
    }

    public String getPart(int index) {
        if (rawParts != null && index >= 0 && index < rawParts.length) {
            return rawParts[index];
        }
        return "";
    }

    /**
     * Giải mã một dòng văn bản nhận được từ socket thành gói tin
     */
    public static NetworkPacket parse(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }
        String[] parts = line.split(ProtocolConstants.DELIMITER_REGEX);
        try {
            CommandType cmd = CommandType.valueOf(parts[0]);
            return new NetworkPacket(cmd, parts);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // Builder methods tạo chuỗi gói tin an toàn
    public static String buildAuth(String role, String deviceId, String key) {
        return buildAuth(role, deviceId, key, "");
    }

    public static String buildAuth(String role, String deviceId, String key, String mac) {
        return CommandType.AUTH + "|" + sanitize(role) + "|" + sanitize(deviceId) + "|" + sanitize(key) + "|" + sanitize(mac);
    }

    public static String buildRing(String tableId, String reason) {
        return CommandType.RING + "|" + sanitize(tableId) + "|" + sanitize(reason);
    }

    public static String buildOrder(String tableId, String itemsSummary, long totalAmount) {
        return CommandType.ORDER + "|" + sanitize(tableId) + "|" + sanitize(itemsSummary) + "|" + totalAmount;
    }

    public static String buildOrderStatusUpdate(int orderId, String tableId, String newStatus) {
        return CommandType.ORDER_STATUS_UPDATE + "|" + orderId + "|" + sanitize(tableId) + "|" + sanitize(newStatus);
    }

    public static String buildChatToReception(String tableId, String message) {
        return CommandType.CHAT_TO_RECEPTION + "|" + sanitize(tableId) + "|" + sanitize(message);
    }

    public static String buildChatToTable(String targetTable, String message) {
        return CommandType.CHAT_TO_TABLE + "|" + sanitize(targetTable) + "|" + sanitize(message);
    }

    public static String buildTableState(String tableId, String state) {
        return CommandType.TABLE_STATE + "|" + sanitize(tableId) + "|" + sanitize(state);
    }

    public static String buildPortShutdown(String deviceId, String reason) {
        return CommandType.PORT_SHUTDOWN + "|" + sanitize(deviceId) + "|" + sanitize(reason);
    }

    public static String buildServerAck(String msg) {
        return CommandType.SERVER_ACK + "|" + sanitize(msg);
    }

    public static String buildGetMenu(String tableId) {
        return CommandType.GET_MENU + "|" + sanitize(tableId);
    }

    public static String buildGetHistory(String deviceId) {
        return CommandType.GET_HISTORY + "|" + sanitize(deviceId);
    }

    // Server -> Client: HISTORY|THOI_GIAN|NGUOI_GUI|NGUOI_NHAN|LOAI(CHAT/RING/ORDER)|NOI_DUNG
    public static String buildHistoryItem(String time, String sender, String receiver, String type, String content) {
        return "HISTORY|" + sanitize(time) + "|" + sanitize(sender) + "|" + sanitize(receiver) + "|" + sanitize(type) + "|" + sanitize(content);
    }

    // Server -> Tablet: MENU_ITEM|DANH_MUC|TEN_MON|GIA|ANH_BASE64
    // Ảnh là byte nhị phân nên phải đổi sang Base64 (chỉ gồm A-Z a-z 0-9 + / =, không chứa '|' hay xuống dòng)
    public static String buildMenuItem(String category, String itemName, long price, byte[] imageData) {
        String image = (imageData == null || imageData.length == 0) ? "" : Base64.getEncoder().encodeToString(imageData);
        return "MENU_ITEM|" + sanitize(category) + "|" + sanitize(itemName) + "|" + price + "|" + image;
    }

    /**
     * Lọc và chuẩn hóa dữ liệu đầu vào chống phá vỡ định dạng gói tin
     */
    private static String sanitize(String input) {
        if (input == null) return "";
        return input.replace("|", "/").replace("\n", " ").replace("\r", "");
    }
}
