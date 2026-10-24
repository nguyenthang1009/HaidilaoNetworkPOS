package com.haidilao.pos.security;

import com.haidilao.pos.database.DatabaseManager;
import com.haidilao.pos.model.DeviceRole;
import com.haidilao.pos.model.NetworkDevice;
import com.haidilao.pos.protocol.CommandType;
import com.haidilao.pos.protocol.ProtocolConstants;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kiểm soát truy cập của Server:
 * - Xác thực thiết bị khi bắt tay (khóa thiết bị theo vai trò, thiết bị có đang bị khóa không)
 * - Chống dò mật khẩu nhân viên (tạm chặn IP sai quá nhiều lần)
 * - Phân quyền lệnh theo vai trò (RBAC whitelist)
 */
public class FirewallManager {
    private static final long FAIL_WINDOW_MS = 60_000;

    // Thời điểm các lần đăng nhập sai gần đây của từng IP
    private final Map<String, Deque<Long>> loginFailures = new ConcurrentHashMap<>();

    /**
     * Kiểm tra khóa thiết bị và trạng thái khóa khi Client bắt tay
     */
    public boolean validateDevice(String roleStr, String deviceId, String key, StringBuilder errorReason) {
        if (deviceId == null || !deviceId.matches("[A-Za-z0-9_\\-]{1,30}")) {
            errorReason.append("Tên thiết bị không hợp lệ (chỉ gồm chữ không dấu, số, _ và -, tối đa 30 ký tự)!");
            return false;
        }
        DeviceRole role = parseRole(roleStr);
        String expectedKey;
        switch (role) {
            case RECEPTION:
            case KITCHEN:
            case WAITER:
                expectedKey = ProtocolConstants.KEY_STAFF;
                break;
            case TABLET:
                expectedKey = ProtocolConstants.KEY_TABLET;
                break;
            default:
                errorReason.append("Vai trò thiết bị không hợp lệ!");
                return false;
        }
        if (!expectedKey.equals(key)) {
            errorReason.append("Sai khóa thiết bị (Invalid Token)!");
            return false;
        }

        NetworkDevice dev = DatabaseManager.getDeviceById(deviceId);
        if (dev != null && dev.isLocked()) {
            errorReason.append("Thiết bị này đã bị quản trị viên khóa. Liên hệ quản lý để mở khóa.");
            return false;
        }
        return true;
    }

    public static DeviceRole parseRole(String roleStr) {
        if ("RECEPTION".equalsIgnoreCase(roleStr)) return DeviceRole.RECEPTION;
        if ("KITCHEN".equalsIgnoreCase(roleStr)) return DeviceRole.KITCHEN;
        if ("TABLET".equalsIgnoreCase(roleStr)) return DeviceRole.TABLET;
        if ("WAITER".equalsIgnoreCase(roleStr)) return DeviceRole.WAITER;
        return DeviceRole.UNKNOWN;
    }

    /** IP này có đang bị tạm chặn đăng nhập không (sai mật khẩu quá nhiều lần trong 1 phút) */
    public boolean isLoginBlocked(String ip) {
        Deque<Long> q = loginFailures.get(ip);
        if (q == null) return false;
        synchronized (q) {
            prune(q);
            return q.size() >= ProtocolConstants.MAX_LOGIN_FAILS;
        }
    }

    public void recordLoginFailure(String ip) {
        Deque<Long> q = loginFailures.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q);
            q.addLast(System.currentTimeMillis());
        }
    }

    public void clearLoginFailures(String ip) {
        loginFailures.remove(ip);
    }

    private static void prune(Deque<Long> q) {
        long now = System.currentTimeMillis();
        while (!q.isEmpty() && now - q.peekFirst() > FAIL_WINDOW_MS) {
            q.removeFirst();
        }
    }

    // Danh sách lệnh MỖI vai trò được phép gửi sau khi đăng nhập (whitelist).
    // Lệnh nào không nằm trong danh sách đều bị từ chối, kể cả lệnh mới thêm sau này.
    private static final Set<CommandType> TABLET_ALLOWED = EnumSet.of(
            CommandType.PING, CommandType.GET_MENU, CommandType.GET_HISTORY,
            CommandType.ORDER, CommandType.RING, CommandType.CHAT_TO_RECEPTION,
            CommandType.GET_MY_ORDERS, CommandType.GET_BILL, CommandType.PAY_REQUEST);

    private static final Set<CommandType> RECEPTION_ALLOWED = EnumSet.of(
            CommandType.PING, CommandType.GET_HISTORY,
            CommandType.GET_TABLES, CommandType.GET_TABLE_DETAIL, CommandType.SAVE_SESSION,
            CommandType.CHECKOUT, CommandType.SET_TABLE_STATUS, CommandType.MOVE_TABLE, CommandType.GET_BILL, CommandType.CANCEL_SESSION,
            CommandType.GET_RESERVATIONS, CommandType.SAVE_RESERVATION, CommandType.RESERVATION_STATUS, CommandType.CHECK_IN,
            CommandType.CHAT_TO_TABLE, CommandType.ORDER_STATUS_UPDATE, CommandType.GET_CHAT, CommandType.GET_CONVERSATIONS);

    // Phục vụ: chỉ thao tác trên các bàn được Lễ tân giao (Server kiểm tra thêm trong từng lệnh)
    private static final Set<CommandType> WAITER_ALLOWED = EnumSet.of(
            CommandType.PING, CommandType.GET_MY_TABLES, CommandType.GET_CHAT,
            CommandType.CHAT_TO_TABLE, CommandType.ORDER_STATUS_UPDATE);

    private static final Set<CommandType> KITCHEN_ALLOWED = EnumSet.of(
            CommandType.PING, CommandType.GET_KITCHEN_ORDERS, CommandType.KITCHEN_STATUS);

    /**
     * Kiểm tra phân quyền theo vai trò (Role-Based Access Control - RBAC)
     * Ví dụ: Tablet không được gửi CHAT_TO_TABLE (giả làm Lễ tân nhắn sang bàn khác) hay CHECKOUT,
     * Bếp không được thanh toán, Lễ tân không được gửi ORDER / RING (giả làm khách).
     */
    public boolean checkPermission(DeviceRole role, CommandType command, StringBuilder denyReason) {
        Set<CommandType> allowed;
        if (role == DeviceRole.TABLET) {
            allowed = TABLET_ALLOWED;
        } else if (role == DeviceRole.RECEPTION) {
            allowed = RECEPTION_ALLOWED;
        } else if (role == DeviceRole.KITCHEN) {
            allowed = KITCHEN_ALLOWED;
        } else if (role == DeviceRole.WAITER) {
            allowed = WAITER_ALLOWED;
        } else {
            denyReason.append("[RBAC-VIOLATION] Thiết bị chưa xác định vai trò, từ chối lệnh: ").append(command);
            return false;
        }

        if (!allowed.contains(command)) {
            denyReason.append("[RBAC-VIOLATION] Vai trò ").append(role).append(" không có quyền gửi lệnh: ").append(command);
            return false;
        }
        return true;
    }
}
