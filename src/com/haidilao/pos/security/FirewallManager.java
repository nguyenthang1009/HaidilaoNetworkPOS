package com.haidilao.pos.security;

import com.haidilao.pos.database.DatabaseManager;
import com.haidilao.pos.model.DeviceRole;
import com.haidilao.pos.model.NetworkDevice;
import com.haidilao.pos.protocol.CommandType;
import com.haidilao.pos.protocol.ProtocolConstants;

import java.util.ArrayList;
import java.util.List;

/**
 * Bộ máy Tường Lửa Mạng (Network Firewall & Port Security Engine)
 * Đảm bảo an toàn thông tin mạng, kiểm soát ACL và phân quyền RBAC
 */
public class FirewallManager {
    private final List<AclRule> rules = new ArrayList<>();

    public FirewallManager() {
        initDefaultAclRules();
    }

    private void initDefaultAclRules() {
        rules.add(new AclRule(10, "PERMIT", "TCP", "192.168.20.0/24", "192.168.10.50", 8888, "Cho phép Tablet Wi-Fi kết nối POS_Server"));
        rules.add(new AclRule(20, "PERMIT", "TCP", "192.168.10.0/24", "192.168.10.50", 8888, "Cho phép Quản trị & Lễ tân kết nối POS_Server"));
        rules.add(new AclRule(30, "DENY", "IP", "192.168.20.0/24", "192.168.10.0/24", 0, "Chặn tuyệt đối kết nối P2P trực tiếp Tablet sang Lễ tân"));
        rules.add(new AclRule(99, "DENY", "IP", "ANY", "ANY", 0, "Implicit Deny All"));
    }

    public List<AclRule> getRules() {
        return rules;
    }

    /**
     * Xác thực Port Security & Phân quyền VLAN khi Client bắt tay
     */
    public boolean validatePortSecurity(String roleStr, String deviceId, String key, StringBuilder errorReason) {
        // 1. Kiểm tra khóa xác thực an ninh
        if ("RECEPTION".equalsIgnoreCase(roleStr)) {
            if (!ProtocolConstants.KEY_RECEPTION.equals(key)) {
                errorReason.append("Sai Khóa Quản Trị Hệ Thống (Invalid Token)!");
                return false;
            }
        } else if ("TABLET".equalsIgnoreCase(roleStr)) {
            if (!ProtocolConstants.KEY_TABLET.equals(key)) {
                errorReason.append("Sai Khóa Truy Cập Khách Hàng (Invalid Token)!");
                return false;
            }
        } else {
            errorReason.append("Vai trò thiết bị không hợp lệ!");
            return false;
        }

        // 2. Tra cứu Port Security trong bảng network_devices từ MySQL
        NetworkDevice dev = DatabaseManager.getDeviceById(deviceId);
        if (dev != null && "ERR_DISABLE".equalsIgnoreCase(dev.getSecurityStatus())) {
            errorReason.append("Cổng Switch của thiết bị này đang bị khóa vi phạm (Err-Disable)!");
            return false;
        }

        return true;
    }

    /**
     * Kiểm tra phân quyền theo vai trò (Role-Based Access Control - RBAC)
     */
    public boolean checkPermission(DeviceRole role, CommandType command, StringBuilder denyReason) {
        if (role == DeviceRole.TABLET) {
            // Tablet khách hàng chỉ được phép gửi chuông, đặt món, nhắn tin cho lễ tân
            if (command == CommandType.ORDER_STATUS_UPDATE || command == CommandType.PORT_SHUTDOWN) {
                denyReason.append("[RBAC-VIOLATION] Tablet không có quyền thực thi lệnh quản trị: ").append(command);
                return false;
            }
        } else if (role == DeviceRole.RECEPTION) {
            // Lễ tân không có quyền shutdown cổng switch
            if (command == CommandType.PORT_SHUTDOWN) {
                denyReason.append("[RBAC-VIOLATION] Quầy Lễ Tân không có quyền khóa cổng thiết bị!");
                return false;
            }
        }
        return true;
    }
}
