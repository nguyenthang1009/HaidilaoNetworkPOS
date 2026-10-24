package com.haidilao.pos.protocol;

import com.haidilao.pos.model.OrderLine;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Lớp đóng gói và giải mã gói tin tầng ứng dụng qua TCP Socket.
 *
 * Mỗi bản tin là MỘT dòng: LỆNH|trường 1|trường 2|...
 * Trường chữ tự do (tên khách, ghi chú, tên món...) được mã hóa URL bằng enc() nên không bao giờ chứa
 * ký tự | ~ ; hay xuống dòng, bên nhận giải mã lại bằng dec().
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

    /** Trường thứ index đã giải mã URL */
    public String getText(int index) {
        return dec(getPart(index));
    }

    /** Trường thứ index đổi sang số nguyên, sai định dạng thì trả về def */
    public int getInt(int index, int def) {
        return parseInt(getPart(index), def);
    }

    public long getLong(int index, long def) {
        try {
            return Long.parseLong(getPart(index).trim());
        } catch (NumberFormatException e) {
            return def;
        }
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

    // ------------------------------------------------------------------ mã hóa trường chữ

    /** Mã hóa URL một trường chữ tự do (null -> "") */
    public static String enc(String s) {
        return (s == null) ? "" : URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Giải mã trường đã mã hóa bằng enc(). Chuỗi sai định dạng thì trả nguyên văn. */
    public static String dec(String s) {
        if (s == null || s.isEmpty()) return "";
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    /** Tách một dòng Server gửi xuống thành các trường (giữ cả trường rỗng ở cuối) */
    public static String[] split(String line) {
        return line.split(ProtocolConstants.DELIMITER_REGEX, -1);
    }

    public static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return def;
        }
    }

    // ------------------------------------------------------------------ Client -> Server

    public static String buildAuth(String role, String deviceId, String key) {
        return buildAuth(role, deviceId, key, "");
    }

    public static String buildAuth(String role, String deviceId, String key, String mac) {
        return CommandType.AUTH + "|" + sanitize(role) + "|" + sanitize(deviceId) + "|" + sanitize(key) + "|" + sanitize(mac);
    }

    /** AUTH|VAI_TRÒ|THIẾT_BỊ|KHÓA|MAC|TÀI_KHOẢN|MẬT_KHẨU (máy nhân viên: Lễ tân, Bếp) */
    public static String buildAuth(String role, String deviceId, String key, String mac, String username, String password) {
        return buildAuth(role, deviceId, key, mac) + "|" + enc(username) + "|" + enc(password);
    }

    public static String buildRing(String tableId, String reason) {
        return CommandType.RING + "|" + sanitize(tableId) + "|" + sanitize(reason);
    }

    /**
     * ORDER|BÀN|GHI_CHÚ_ĐƠN|DÒNG;DÒNG;...   mỗi dòng: LOẠI~MÃ~SỐ_LƯỢNG~GHI_CHÚ_MÓN (LOẠI: M = món, C = combo).
     * Không gửi giá: Server tự lấy giá trong database.
     */
    public static String buildOrder(String tableId, String orderNote, List<OrderLine> lines) {
        StringBuilder sb = new StringBuilder();
        for (OrderLine l : lines) {
            if (sb.length() > 0) sb.append(';');
            sb.append(l.getType()).append('~').append(l.getRefId()).append('~').append(l.getQuantity()).append('~').append(enc(l.getNote()));
        }
        return CommandType.ORDER + "|" + sanitize(tableId) + "|" + enc(orderNote) + "|" + sb;
    }

    /**
     * Đọc phần danh sách dòng của lệnh ORDER. Dòng sai định dạng bị bỏ qua (giá và tên để trống, Server tự điền).
     */
    public static List<OrderLine> parseOrderLines(String spec) {
        List<OrderLine> lines = new ArrayList<>();
        if (spec == null || spec.isEmpty()) return lines;
        for (String part : spec.split(";")) {
            String[] f = part.split("~", -1);
            if (f.length < 3 || f[0].length() != 1) continue;
            char type = f[0].charAt(0);
            if (type != OrderLine.TYPE_ITEM && type != OrderLine.TYPE_COMBO) continue;
            int id = parseInt(f[1], -1);
            int qty = parseInt(f[2], -1);
            if (id <= 0 || qty <= 0) continue;
            lines.add(new OrderLine(type, id, "", qty, 0, f.length > 3 ? dec(f[3]) : ""));
        }
        return lines;
    }

    public static String buildOrderStatusUpdate(int orderId, String tableId, String newStatus) {
        return CommandType.ORDER_STATUS_UPDATE + "|" + orderId + "|" + sanitize(tableId) + "|" + enc(newStatus);
    }

    public static String buildChatToReception(String tableId, String message) {
        return CommandType.CHAT_TO_RECEPTION + "|" + sanitize(tableId) + "|" + sanitize(message);
    }

    public static String buildChatToTable(String targetTable, String message) {
        return CommandType.CHAT_TO_TABLE + "|" + sanitize(targetTable) + "|" + sanitize(message);
    }

    public static String buildGetMenu(String tableId) {
        return CommandType.GET_MENU + "|" + sanitize(tableId);
    }

    public static String buildGetHistory(String deviceId) {
        return CommandType.GET_HISTORY + "|" + sanitize(deviceId);
    }

    public static String buildGetTables() {
        return CommandType.GET_TABLES.name();
    }

    public static String buildGetTableDetail(String tableId) {
        return CommandType.GET_TABLE_DETAIL + "|" + sanitize(tableId);
    }

    /** SAVE_SESSION|BÀN|TÊN_KHÁCH|SĐT|SỐ_KHÁCH|MÃ_NV_PHỤC_VỤ|GHI_CHÚ (mở bàn hoặc sửa thông tin khách) */
    public static String buildSaveSession(String tableId, String customerName, String phone, int guests, int waiterId, String note) {
        return CommandType.SAVE_SESSION + "|" + sanitize(tableId) + "|" + enc(customerName) + "|" + enc(phone)
                + "|" + guests + "|" + waiterId + "|" + enc(note);
    }

    /** CHECKOUT|BÀN|GIẢM_GIÁ|HÌNH_THỨC_THANH_TOÁN */
    public static String buildCheckout(String tableId, long discount, String paymentMethod) {
        return CommandType.CHECKOUT + "|" + sanitize(tableId) + "|" + discount + "|" + enc(paymentMethod);
    }

    public static String buildSetTableStatus(String tableId, String status) {
        return CommandType.SET_TABLE_STATUS + "|" + sanitize(tableId) + "|" + enc(status);
    }

    /** GET_CHAT|BÀN|PHẠM_VI (SESSION = lượt khách hiện tại, TODAY = hôm nay, WEEK = 7 ngày) */
    public static String buildGetChat(String tableId, String scope) {
        return CommandType.GET_CHAT + "|" + sanitize(tableId) + "|" + sanitize(scope);
    }

    public static String buildGetConversations() {
        return CommandType.GET_CONVERSATIONS.name();
    }

    public static String buildGetMyTables() {
        return CommandType.GET_MY_TABLES.name();
    }

    /** GET_KITCHEN_ORDERS|PENDING (đơn chưa xong) hoặc GET_KITCHEN_ORDERS|ALL (mọi đơn hôm nay) */
    public static String buildGetKitchenOrders(boolean pendingOnly) {
        return CommandType.GET_KITCHEN_ORDERS + "|" + (pendingOnly ? "PENDING" : "ALL");
    }

    public static String buildKitchenStatus(int orderId, String status) {
        return CommandType.KITCHEN_STATUS + "|" + orderId + "|" + enc(status);
    }

    public static String buildGetMyOrders() {
        return CommandType.GET_MY_ORDERS.name();
    }

    public static String buildGetBill() {
        return CommandType.GET_BILL.name();
    }

    /** Lễ tân xem hóa đơn tạm tính của một bàn: GET_BILL|BÀN */
    public static String buildGetBill(String tableId) {
        return CommandType.GET_BILL + "|" + sanitize(tableId);
    }

    /** PAY_REQUEST|HÌNH_THỨC (Tiền mặt / Chuyển khoản) */
    public static String buildPayRequest(String method) {
        return CommandType.PAY_REQUEST + "|" + enc(method);
    }

    /** CANCEL_SESSION|BÀN|LÝ_DO (khách về khi chưa gọi món) */
    public static String buildCancelSession(String tableId, String reason) {
        return CommandType.CANCEL_SESSION + "|" + sanitize(tableId) + "|" + enc(reason);
    }

    /** MOVE_TABLE|BÀN_HIỆN_TẠI|BÀN_MỚI */
    public static String buildMoveTable(String fromTable, String toTable) {
        return CommandType.MOVE_TABLE + "|" + sanitize(fromTable) + "|" + sanitize(toTable);
    }

    /** GET_RESERVATIONS|yyyy-MM-dd */
    public static String buildGetReservations(String date) {
        return CommandType.GET_RESERVATIONS + "|" + sanitize(date);
    }

    /**
     * SAVE_RESERVATION|MÃ(0 = thêm mới)|TÊN|SĐT|SỐ_KHÁCH|yyyy-MM-dd HH:mm|BÀN(rỗng = chưa chọn)|KHU_VỰC|TIỀN_CỌC|GHI_CHÚ
     */
    public static String buildSaveReservation(int id, String name, String phone, int guests, String dateTime,
                                              String tableId, String area, long deposit, String note) {
        return CommandType.SAVE_RESERVATION + "|" + id + "|" + enc(name) + "|" + enc(phone) + "|" + guests + "|" + enc(dateTime)
                + "|" + sanitize(tableId) + "|" + enc(area) + "|" + deposit + "|" + enc(note);
    }

    /** RESERVATION_STATUS|MÃ|TRẠNG_THÁI (ĐÃ HỦY / KHÔNG ĐẾN) */
    public static String buildReservationStatus(int id, String status) {
        return CommandType.RESERVATION_STATUS + "|" + id + "|" + enc(status);
    }

    /** CHECK_IN|MÃ_LƯỢT_ĐẶT|BÀN(rỗng = bàn đã đặt)|MÃ_PHỤC_VỤ */
    public static String buildCheckIn(int reservationId, String tableId, int waiterId) {
        return CommandType.CHECK_IN + "|" + reservationId + "|" + sanitize(tableId) + "|" + waiterId;
    }

    // ------------------------------------------------------------------ Server -> Client

    public static String buildTableState(String tableId, String state) {
        return CommandType.TABLE_STATE + "|" + sanitize(tableId) + "|" + sanitize(state);
    }

    public static String buildServerAck(String msg) {
        return CommandType.SERVER_ACK + "|" + sanitize(msg);
    }

    // HISTORY|THOI_GIAN|NGUOI_GUI|NGUOI_NHAN|LOAI(CHAT/RING/ORDER)|NOI_DUNG|TEN_NGUOI_GUI
    public static String buildHistoryItem(String time, String sender, String receiver, String type, String content, String senderName) {
        return "HISTORY|" + sanitize(time) + "|" + sanitize(sender) + "|" + sanitize(receiver) + "|" + sanitize(type) + "|" + sanitize(content)
                + "|" + enc(senderName);
    }

    // MENU_ITEM|MÃ_MÓN|DANH_MUC|TEN_MON|GIA|ANH_BASE64
    // Ảnh là byte nhị phân nên phải đổi sang Base64 (chỉ gồm A-Z a-z 0-9 + / =, không chứa '|' hay xuống dòng)
    public static String buildMenuItem(int itemId, String category, String itemName, long price, byte[] imageData) {
        String image = (imageData == null || imageData.length == 0) ? "" : Base64.getEncoder().encodeToString(imageData);
        return "MENU_ITEM|" + itemId + "|" + sanitize(category) + "|" + sanitize(itemName) + "|" + price + "|" + image;
    }

    // COMBO_ITEM|MÃ_COMBO|TÊN|GIÁ|MÔ_TẢ|CÁC_MÓN_TRONG_COMBO|ẢNH_BASE64
    public static String buildComboItem(int comboId, String name, long price, String description, String itemsSummary, byte[] imageData) {
        String image = (imageData == null || imageData.length == 0) ? "" : Base64.getEncoder().encodeToString(imageData);
        return "COMBO_ITEM|" + comboId + "|" + enc(name) + "|" + price + "|" + enc(description) + "|" + enc(itemsSummary) + "|" + image;
    }

    /**
     * Danh sách món cho Bếp: TÊN~SỐ_LƯỢNG~GHI_CHÚ;TÊN~SỐ_LƯỢNG~GHI_CHÚ...
     * names[i] là tên hiển thị của dòng i (combo đã kèm các món bên trong).
     */
    public static String encodeKitchenLines(List<OrderLine> lines, List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (sb.length() > 0) sb.append(';');
            sb.append(enc(names.get(i))).append('~').append(lines.get(i).getQuantity()).append('~').append(enc(lines.get(i).getNote()));
        }
        return sb.toString();
    }

    /** Ngược lại encodeKitchenLines: mỗi phần tử là {tên, số lượng, ghi chú} */
    public static List<String[]> decodeKitchenLines(String spec) {
        List<String[]> list = new ArrayList<>();
        if (spec == null || spec.isEmpty()) return list;
        for (String part : spec.split(";")) {
            String[] f = part.split("~", -1);
            list.add(new String[]{dec(f[0]), f.length > 1 ? f[1] : "", f.length > 2 ? dec(f[2]) : ""});
        }
        return list;
    }

    /**
     * Lọc và chuẩn hóa dữ liệu đầu vào chống phá vỡ định dạng gói tin
     */
    private static String sanitize(String input) {
        if (input == null) return "";
        return input.replace("|", "/").replace("\n", " ").replace("\r", "");
    }
}
