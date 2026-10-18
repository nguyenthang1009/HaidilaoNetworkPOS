package com.haidilao.pos.server;

import com.haidilao.pos.database.DatabaseManager;
import com.haidilao.pos.model.ChatMessage;
import com.haidilao.pos.model.DeviceRole;
import com.haidilao.pos.model.MenuItem;
import com.haidilao.pos.model.NetworkDevice;
import com.haidilao.pos.protocol.CommandType;
import com.haidilao.pos.protocol.NetworkPacket;
import com.haidilao.pos.protocol.ProtocolConstants;
import com.haidilao.pos.security.CryptoManager;
import com.haidilao.pos.security.FirewallManager;

import com.haidilao.pos.model.Order;
import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.text.SimpleDateFormat;
import java.util.List;

/**
 * Quản lý phiên làm việc của một Client kết nối tới POS Server
 */
public class ClientSession implements Runnable {
    private final Socket socket;
    private final PosServer server;
    private final FirewallManager firewall;
    private BufferedReader in;
    private PrintWriter out;

    private String deviceId = "UNKNOWN";
    private DeviceRole role = DeviceRole.UNKNOWN;
    private String simulatedIp = "192.168.20.100";
    private String macAddress = "0000.0000.0000";
    private String vlanName = "VLAN 20 (Khách Wi-Fi)";
    private boolean authenticated = false;
    private String remoteIp = "";   // IP thật của máy đang kết nối (lấy từ socket), dùng để ghi log an ninh

    public ClientSession(Socket socket, PosServer server, FirewallManager firewall) {
        this.socket = socket;
        this.server = server;
        this.firewall = firewall;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public DeviceRole getRole() {
        return role;
    }

    public String getSimulatedIp() {
        return simulatedIp;
    }

    public String getMacAddress() {
        return macAddress;
    }

    public String getVlanName() {
        return vlanName;
    }

    public boolean isConnected() {
        return authenticated && socket != null && !socket.isClosed();
    }

    public void sendMessage(String msg) {
        if (out != null) {
            out.println(CryptoManager.encrypt(msg));
        }
    }

    public void closeConnection() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {}
    }

    @Override
    public void run() {
        try {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
            remoteIp = socket.getInetAddress().getHostAddress();

            // Heartbeat: client gửi PING mỗi 5 giây. Quá 15 giây không nhận được gói nào
            // thì readLine() ném SocketTimeoutException -> coi thiết bị đã mất kết nối (rớt Wi-Fi, tắt máy đột ngột...)
            socket.setSoTimeout(ProtocolConstants.HEARTBEAT_TIMEOUT_MS);

            // 1. Nhận gói tin bắt tay AUTH đầu tiên (Mã hóa AES-128)
            String authLine = in.readLine();
            boolean isEnc = CryptoManager.isEncrypted(authLine);
            String plainAuth = CryptoManager.decrypt(authLine);
            NetworkPacket authPacket = NetworkPacket.parse(plainAuth);
            if (authPacket == null || authPacket.getType() != CommandType.AUTH) {
                server.log("[ACL-DROP] Phát hiện kết nối không có gói bắt tay AUTH hoặc sai mã hóa AES. Ngắt kết nối ngay!");
                DatabaseManager.saveSecurityLog("UNKNOWN", remoteIp, "NO_AUTH", "Kết nối không gửi gói AUTH hợp lệ");
                closeConnection();
                return;
            }
            if (isEnc) {
                server.log("[CRYPTO-AES128] Gói tin bắt tay từ [" + authPacket.getPart(2) + "] đã được mã hóa an toàn trên đường truyền.");
            }

            // Gói tin dạng: AUTH|ROLE|DEVICE_ID|KEY|REAL_MAC
            String roleStr = authPacket.getPart(1);
            this.deviceId = authPacket.getPart(2);
            String authKey = authPacket.getPart(3);
            String clientMac = authPacket.getPart(4);

            StringBuilder errorReason = new StringBuilder();
            if (!firewall.validatePortSecurity(roleStr, deviceId, authKey, errorReason)) {
                server.log("[PORT-SECURITY VIOLATION] Từ chối kết nối từ " + deviceId + ": " + errorReason);
                sendMessage(CommandType.AUTH_FAIL + "|" + errorReason);
                // Ghi vào security_logs (không có khóa ngoại) nên thiết bị lạ chưa đăng ký vẫn ghi được
                DatabaseManager.saveSecurityLog(deviceId, remoteIp, "AUTH_FAIL", errorReason.toString());
                closeConnection();
                return;
            }

            // Lấy địa chỉ IP thực tế từ Socket
            String realSocketIp = socket.getInetAddress().getHostAddress();
            boolean isLocalhost = "127.0.0.1".equals(realSocketIp) || "0:0:0:0:0:0:0:1".equals(realSocketIp) || "localhost".equalsIgnoreCase(realSocketIp);

            // Đồng bộ thông tin IP / MAC / VLAN từ cơ sở dữ liệu network_devices hoặc cấp phát động
            NetworkDevice dev = DatabaseManager.getDeviceById(deviceId);

            if (isLocalhost) {
                // Chế độ chạy trên 1 máy (Đồng bộ chuẩn sơ đồ Cisco Packet Tracer)
                if (dev != null) {
                    this.simulatedIp = dev.getIpAddress();
                    this.macAddress = (clientMac != null && !clientMac.isEmpty()) ? clientMac : dev.getMacAddress();
                    this.vlanName = dev.getVlanName();
                } else {
                    // Thiết bị mới chưa có trong sơ đồ (Ví dụ Tablet_Ban4, Tablet_Ban5, quầy Lễ tân thứ 2...)
                    boolean isReception = "RECEPTION".equalsIgnoreCase(roleStr);
                    int num = isReception ? 21 + Math.abs(deviceId.hashCode() % 9) : 100 + Math.abs(deviceId.hashCode() % 50);
                    this.simulatedIp = (isReception ? "192.168.10." : "192.168.20.") + num;
                    this.macAddress = (clientMac != null && !clientMac.isEmpty()) ? clientMac : ("0040.0BD3.2C" + (num % 90 + 10));
                    this.vlanName = isReception ? ProtocolConstants.VLAN_ADMIN : ProtocolConstants.VLAN_GUEST;
                    DatabaseManager.registerOrUpdateDevice(new NetworkDevice(deviceId, vlanName, simulatedIp, macAddress, "DHCP_ACTIVE"));
                }
            } else {
                // Chế độ chạy mạng LAN THỰC TẾ qua nhiều máy tính (Laptop/Tablet thật kết nối Wi-Fi/Switch)
                this.simulatedIp = realSocketIp;
                this.macAddress = (clientMac != null && !clientMac.isEmpty()) ? clientMac : ("LAN.MAC." + realSocketIp.replace(".", ""));
                this.vlanName = "RECEPTION".equalsIgnoreCase(roleStr) ? ProtocolConstants.VLAN_ADMIN : ProtocolConstants.VLAN_GUEST;
                DatabaseManager.registerOrUpdateDevice(new NetworkDevice(deviceId, vlanName, simulatedIp, macAddress, "DHCP_ACTIVE"));
            }

            if ("RECEPTION".equalsIgnoreCase(roleStr)) {
                this.role = DeviceRole.RECEPTION;
                this.authenticated = true;
                // Gửi AUTH_ACK TRƯỚC khi đăng ký phiên: client chờ AUTH_ACK là gói đầu tiên rồi mới báo "đã kết nối"
                sendMessage(CommandType.AUTH_ACK + "|Chào mừng Quầy Lễ Tân " + deviceId + " (" + vlanName + ") đã kết nối an toàn!");
                server.registerReception(deviceId, this);
                server.log("[SW_CORE Fa0/2] Quầy Lễ Tân [" + deviceId + " - " + simulatedIp + " - " + macAddress + "] Port-Security Sticky OK.");

                // Đồng bộ danh sách đơn hàng đã lưu trong MySQL xuống quầy Lễ tân qua TCP Socket
                // Dùng lệnh OLD_ORDER (không kêu bíp, giữ trạng thái cũ). Gửi từ đơn cũ nhất -> mới nhất
                // vì bên Lễ tân chèn dòng lên đầu bảng, nên đơn mới nhất sẽ nằm trên cùng.
                try {
                    List<Order> orders = DatabaseManager.getAllOrders();
                    for (int i = orders.size() - 1; i >= 0; i--) {
                        Order o = orders.get(i);
                        sendMessage("OLD_ORDER|" + o.getOrderId() + "|" + o.getTableId() + "|" + o.getItemsSummary() + "|" + o.getTotalAmount() + "|" + o.getOrderStatus());
                    }
                } catch (Exception ignored) {}
            } else {
                this.role = DeviceRole.TABLET;
                this.authenticated = true;
                sendMessage(CommandType.AUTH_ACK + "|Chào mừng " + deviceId + " đã kết nối vào hệ thống gọi món Haidilao!");
                server.registerTablet(deviceId, this);
                server.log("[ROUTER ACL Rule 10 & DHCP] Cho phép thiết bị " + deviceId + " [IP: " + simulatedIp + ", MAC: " + macAddress + "] kết nối POS_Server:8888.");
            }

            // 2. Vòng lặp nhận và xử lý gói tin
            String line;
            while ((line = in.readLine()) != null) {
                // Tự động giải mã gói tin dữ liệu an toàn nhận từ mạng (AES-128)
                String plainLine = CryptoManager.decrypt(line);
                NetworkPacket packet = NetworkPacket.parse(plainLine);
                if (packet == null) continue;

                // Kiểm tra phân quyền RBAC
                StringBuilder denyReason = new StringBuilder();
                if (!firewall.checkPermission(this.role, packet.getType(), denyReason)) {
                    server.log(denyReason.toString());
                    DatabaseManager.saveSecurityLog(deviceId, remoteIp, "RBAC_DENY", denyReason.toString());
                    sendMessage(CommandType.SERVER_ACK + "|" + denyReason);
                    continue;
                }

                handlePacket(packet);
            }

        } catch (SocketTimeoutException e) {
            server.log("[HEARTBEAT] Quá " + ProtocolConstants.HEARTBEAT_TIMEOUT_MS / 1000 + " giây không nhận được tín hiệu từ "
                    + deviceId + " -> coi như mất kết nối (rớt mạng / tắt máy đột ngột).");
        } catch (IOException e) {
            server.log("[DISCONNECT] Thiết bị " + deviceId + " đã ngắt kết nối socket.");
        } finally {
            if (this.role == DeviceRole.TABLET) {
                server.unregisterTablet(deviceId, this);
            } else if (this.role == DeviceRole.RECEPTION) {
                server.unregisterReception(deviceId, this);
            }
            closeConnection();
        }
    }

    private void handlePacket(NetworkPacket packet) {
        CommandType cmd = packet.getType();
        switch (cmd) {
            case RING: {
                // RING|TABLE_ID|REASON
                String reason = packet.getPart(2);
                if (reason.isEmpty()) reason = "Gọi nhân viên phục vụ tại bàn";
                server.log("[BELL-RELAY] " + deviceId + " -> PC_LeTan: " + reason);
                DatabaseManager.saveChatOrBellLog(deviceId, "PC_LeTan", "RING", reason);
                server.broadcastToReception("RING|" + deviceId + "|" + reason);
                if (server.getReceptionSessions().isEmpty()) {
                    sendMessage(NetworkPacket.buildServerAck("Lễ tân chưa trực tuyến. Yêu cầu [" + reason + "] đã được lưu, nhân viên sẽ thấy khi vào ca."));
                } else {
                    sendMessage(NetworkPacket.buildServerAck("Đã chuyển yêu cầu [" + reason + "] tới quầy Lễ tân!"));
                }
                break;
            }

            case ORDER: {
                // ORDER|TABLE_ID|ITEMS|TOTAL
                String items = packet.getPart(2);
                long total = 0;
                try {
                    total = Long.parseLong(packet.getPart(3));
                } catch (NumberFormatException ignored) {}

                int orderId = DatabaseManager.insertOrder(deviceId, items, total);
                long updatedRevenue = DatabaseManager.getTotalRevenue();
                server.notifyRevenueChanged(updatedRevenue);

                server.log("[MYSQL-INSERT] Lưu đơn #" + orderId + " từ " + deviceId + " (" + total + " VNĐ) -> Đẩy sang Lễ tân");
                DatabaseManager.saveChatOrBellLog(deviceId, "POS_Server", "ORDER", items + " (" + total + " VNĐ)");
                server.broadcastToReception("NEW_ORDER|" + orderId + "|" + deviceId + "|" + items + "|" + total);
                sendMessage(NetworkPacket.buildServerAck("Đơn hàng #" + orderId + " đã lưu vào MySQL & chuyển tới Lễ tân thành công!"));
                break;
            }

            case CHAT_TO_RECEPTION: {
                // CHAT_TO_RECEPTION|TABLE_ID|MESSAGE
                String chatMsg = packet.getPart(2);
                server.log("[IM-CHAT] " + deviceId + " -> PC_LeTan: \"" + chatMsg + "\"");
                DatabaseManager.saveChatOrBellLog(deviceId, "PC_LeTan", "CHAT", chatMsg);
                server.broadcastToReception("CHAT_FROM_TABLE|" + deviceId + "|" + chatMsg);
                // Báo trạng thái gửi cho khách: DELIVERED = đã tới ít nhất 1 quầy Lễ tân, STORED = chưa có Lễ tân, chỉ lưu MySQL
                sendMessage("CHAT_ACK|" + (server.getReceptionSessions().isEmpty() ? "STORED" : "DELIVERED"));
                break;
            }

            case CHAT_TO_TABLE: {
                // CHAT_TO_TABLE|TARGET_TABLE|MESSAGE
                String targetTable = packet.getPart(1);
                String reply = packet.getPart(2);
                server.log("[IM-REPLY] " + deviceId + " -> " + targetTable + ": \"" + reply + "\"");
                DatabaseManager.saveChatOrBellLog(deviceId, targetTable, "CHAT", reply);
                // Các quầy Lễ tân khác cũng thấy câu trả lời này (tránh 2 quầy cùng trả lời 1 bàn)
                server.broadcastToReceptionExcept("RECEPTION_REPLY|" + deviceId + "|" + targetTable + "|" + reply, this);
                ClientSession targetSession = server.getTabletSession(targetTable);
                if (targetSession != null && targetSession.isConnected()) {
                    targetSession.sendMessage("CHAT_FROM_RECEPTION|" + reply);
                    sendMessage(CommandType.SERVER_ACK + "|Đã chuyển tin phản hồi tới " + targetTable + " thành công.");
                } else {
                    sendMessage(CommandType.SERVER_ACK + "|" + targetTable + " hiện không kết nối. Tin nhắn đã được lưu, bàn sẽ thấy khi kết nối lại.");
                }
                break;
            }

            case PING: {
                // Heartbeat: trả PONG để client biết Server còn sống (không ghi log cho đỡ rối)
                sendMessage(CommandType.PONG.name());
                break;
            }

            case GET_HISTORY: {
                // GET_HISTORY|DEVICE_ID -> trả nhiều dòng HISTORY rồi HISTORY_END|SO_DONG
                // Dùng deviceId của phiên đã xác thực (không tin tham số trong gói) nên bàn này không xem được tin của bàn khác
                List<ChatMessage> history = (role == DeviceRole.TABLET)
                        ? DatabaseManager.getChatHistoryForTable(deviceId, ProtocolConstants.CHAT_HISTORY_LIMIT)
                        : DatabaseManager.getRecentChatHistory(ProtocolConstants.CHAT_HISTORY_LIMIT);
                SimpleDateFormat fmt = new SimpleDateFormat("dd/MM HH:mm");
                for (ChatMessage m : history) {
                    String time = (m.getSentAt() != null) ? fmt.format(m.getSentAt()) : "";
                    sendMessage(NetworkPacket.buildHistoryItem(time, m.getSender(), m.getReceiver(), m.getMessageType(), m.getContent()));
                }
                sendMessage("HISTORY_END|" + history.size());
                break;
            }

            case GET_MENU: {
                // GET_MENU|TABLE_ID -> Server đọc MySQL rồi trả về: MENU_BEGIN, nhiều dòng MENU_ITEM, MENU_END
                // Nhờ vậy Tablet ở máy khác không cần kết nối MySQL (chỉ Server truy cập Database)
                List<MenuItem> menu = DatabaseManager.getAllMenuItems();
                sendMessage("MENU_BEGIN|" + menu.size());
                for (MenuItem item : menu) {
                    sendMessage(NetworkPacket.buildMenuItem(item.getCategory(), item.getItemName(), item.getPrice(), item.getImageData()));
                }
                sendMessage("MENU_END");
                server.log("[MENU-SYNC] Đã gửi " + menu.size() + " món trong thực đơn cho " + deviceId);
                break;
            }

            case ORDER_STATUS_UPDATE: {
                // ORDER_STATUS_UPDATE|ORDER_ID|TABLE_ID|NEW_STATUS
                int orderId = 0;
                try {
                    orderId = Integer.parseInt(packet.getPart(1));
                } catch (NumberFormatException ignored) {}
                String targetTbl = packet.getPart(2);
                String newStatus = packet.getPart(3);

                DatabaseManager.updateOrderStatus(orderId, newStatus);
                server.log("[MYSQL-UPDATE] " + deviceId + " cập nhật đơn #" + orderId + " (" + targetTbl + ") -> " + newStatus);
                // Đồng bộ trạng thái đơn cho mọi quầy Lễ tân đang mở
                server.broadcastToReception("ORDER_UPDATED|" + orderId + "|" + newStatus);
                ClientSession tClient = server.getTabletSession(targetTbl);
                if (tClient != null) {
                    tClient.sendMessage("ORDER_STATUS|Đơn hàng #" + orderId + " của quý khách: " + newStatus);
                }
                break;
            }

            default:
                break;
        }
    }
}
