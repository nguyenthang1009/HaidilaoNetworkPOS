package com.haidilao.pos.server;

import com.haidilao.pos.database.ComboDao;
import com.haidilao.pos.database.DatabaseManager;
import com.haidilao.pos.database.OrderDao;
import com.haidilao.pos.database.ReservationDao;
import com.haidilao.pos.database.SessionDao;
import com.haidilao.pos.database.StaffDao;
import com.haidilao.pos.database.TableDao;
import com.haidilao.pos.model.ChatMessage;
import com.haidilao.pos.model.Combo;
import com.haidilao.pos.model.DeviceRole;
import com.haidilao.pos.model.DiningTable;
import com.haidilao.pos.model.MenuItem;
import com.haidilao.pos.model.NetworkDevice;
import com.haidilao.pos.model.Order;
import com.haidilao.pos.model.Reservation;
import com.haidilao.pos.model.Staff;
import com.haidilao.pos.model.TableSession;
import com.haidilao.pos.protocol.CommandType;
import com.haidilao.pos.protocol.NetworkPacket;
import com.haidilao.pos.protocol.ProtocolConstants;
import com.haidilao.pos.security.CryptoManager;
import com.haidilao.pos.security.FirewallManager;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.sql.Date;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;

import static com.haidilao.pos.protocol.NetworkPacket.enc;

/**
 * Quản lý phiên làm việc của một Client kết nối tới POS Server (một luồng cho mỗi client)
 */
public class ClientSession implements Runnable {
    private final Socket socket;
    private final PosServer server;
    private final FirewallManager firewall;
    private BufferedReader in;
    private PrintWriter out;

    private String deviceId = "UNKNOWN";
    private DeviceRole role = DeviceRole.UNKNOWN;
    private String macAddress = "";
    private boolean authenticated = false;
    private String remoteIp = "";   // IP thật của máy đang kết nối (lấy từ socket)
    private Staff staff;            // nhân viên đăng nhập (Lễ tân, Bếp); Tablet thì null

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

    public String getRemoteIp() {
        return remoteIp;
    }

    public String getMacAddress() {
        return macAddress;
    }

    public int getStaffId() {
        return staff == null ? 0 : staff.getStaffId();
    }

    public String getStaffName() {
        return staff == null ? "" : staff.getFullName();
    }

    public boolean isConnected() {
        return authenticated && socket != null && !socket.isClosed();
    }

    public synchronized void sendMessage(String msg) {
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

            if (!handshake()) {
                closeConnection();
                return;
            }

            // Vòng lặp nhận và xử lý gói tin
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
                    sendMessage(NetworkPacket.buildServerAck(denyReason.toString()));
                    continue;
                }

                try {
                    handlePacket(packet);
                } catch (RuntimeException e) {
                    // Lỗi khi xử lý một lệnh không được làm rớt kết nối của cả thiết bị
                    server.log("[ERROR] Lỗi xử lý lệnh " + packet.getType() + " từ " + deviceId + ": " + e);
                    sendMessage(NetworkPacket.buildServerAck("Server gặp lỗi khi xử lý yêu cầu, vui lòng thử lại."));
                }
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
            } else if (this.role == DeviceRole.KITCHEN) {
                server.unregisterKitchen(deviceId, this);
            } else if (this.role == DeviceRole.WAITER) {
                server.unregisterWaiter(deviceId, this);
            }
            closeConnection();
        }
    }

    /**
     * Bắt tay: AUTH|VAI_TRÒ|THIẾT_BỊ|KHÓA|MAC[|TÀI_KHOẢN|MẬT_KHẨU]
     * Trả về true nếu thiết bị được vào hệ thống.
     */
    private boolean handshake() throws IOException {
        String authLine = in.readLine();
        boolean isEnc = CryptoManager.isEncrypted(authLine);
        NetworkPacket authPacket = NetworkPacket.parse(CryptoManager.decrypt(authLine));
        if (authPacket == null || authPacket.getType() != CommandType.AUTH) {
            server.log("[SECURITY] Kết nối từ " + remoteIp + " không gửi gói bắt tay AUTH hợp lệ (hoặc sai mã hóa AES). Ngắt kết nối!");
            DatabaseManager.saveSecurityLog("UNKNOWN", remoteIp, "NO_AUTH", "Kết nối không gửi gói AUTH hợp lệ");
            return false;
        }
        if (isEnc) {
            server.log("[CRYPTO-AES128] Gói tin bắt tay từ [" + authPacket.getPart(2) + "] đã được mã hóa an toàn trên đường truyền.");
        }

        String roleStr = authPacket.getPart(1);
        String requestedId = authPacket.getPart(2);
        String authKey = authPacket.getPart(3);
        String clientMac = authPacket.getPart(4);

        StringBuilder errorReason = new StringBuilder();
        if (!firewall.validateDevice(roleStr, requestedId, authKey, errorReason)) {
            return reject(requestedId, errorReason.toString());
        }
        DeviceRole requestedRole = FirewallManager.parseRole(roleStr);

        // Máy nhân viên (Lễ tân, Bếp) phải đăng nhập bằng tài khoản nhân viên
        if (requestedRole != DeviceRole.TABLET) {
            if (firewall.isLoginBlocked(remoteIp)) {
                return reject(requestedId, "Đăng nhập sai quá " + ProtocolConstants.MAX_LOGIN_FAILS + " lần. Vui lòng thử lại sau 1 phút.");
            }
            Staff s = StaffDao.authenticate(authPacket.getText(5), authPacket.getText(6));
            if (s == null) {
                firewall.recordLoginFailure(remoteIp);
                return reject(requestedId, "Sai tài khoản hoặc mật khẩu nhân viên (hoặc tài khoản đã bị khóa).");
            }
            boolean allowed = (requestedRole == DeviceRole.RECEPTION) ? s.canUseReception()
                    : (requestedRole == DeviceRole.WAITER) ? s.canUseWaiter() : s.canUseKitchen();
            if (!allowed) {
                return reject(requestedId, "Tài khoản " + s.getUsername() + " (" + s.getRole() + ") không được dùng "
                        + requestedRole.getDescription() + ".");
            }
            firewall.clearLoginFailures(remoteIp);
            this.staff = s;
        }

        this.deviceId = requestedId;
        this.role = requestedRole;
        this.macAddress = clientMac.isEmpty() ? "(không rõ)" : clientMac;
        this.authenticated = true;
        DatabaseManager.registerOrUpdateDevice(new NetworkDevice(deviceId, role.name(), remoteIp, macAddress, NetworkDevice.STATUS_ACTIVE));

        // Gửi AUTH_ACK TRƯỚC khi đăng ký phiên: client chờ AUTH_ACK là gói đầu tiên rồi mới báo "đã kết nối"
        switch (role) {
            case TABLET:
                TableDao.ensureTableForDevice(deviceId); // Tablet mới (vd. Tablet_Ban7) thì tự thêm bàn
                sendMessage(CommandType.AUTH_ACK + "|Chào mừng " + deviceId + " đã kết nối vào hệ thống gọi món!");
                server.registerTablet(deviceId, this);
                server.log("[AUTH] Tablet " + deviceId + " [IP " + remoteIp + ", MAC " + macAddress + "] đã kết nối.");
                break;
            case RECEPTION:
                sendMessage(buildStaffAck("Chào " + staff.getFullName() + ", quầy Lễ tân " + deviceId + " đã kết nối an toàn!"));
                server.registerReception(deviceId, this);
                server.log("[AUTH] Lễ tân " + deviceId + " [IP " + remoteIp + "] - nhân viên " + staff.getUsername() + " đăng nhập.");
                // Đồng bộ các đơn hôm nay xuống quầy (OLD_ORDER: không kêu bíp, giữ trạng thái cũ), cũ trước mới sau
                for (Order o : OrderDao.getReceptionOrders()) {
                    sendMessage(RestaurantService.buildReceptionOrder("OLD_ORDER", o));
                }
                break;
            case WAITER:
                sendMessage(buildStaffAck("Chào " + staff.getFullName() + ", bạn đã vào ca phục vụ."));
                server.registerWaiter(deviceId, this);
                server.log("[AUTH] Phục vụ " + deviceId + " [IP " + remoteIp + "] - nhân viên " + staff.getUsername() + " vào ca.");
                break;
            default:
                sendMessage(buildStaffAck("Chào " + staff.getFullName() + ", màn hình Bếp " + deviceId + " đã kết nối!"));
                server.registerKitchen(deviceId, this);
                server.log("[AUTH] Bếp " + deviceId + " [IP " + remoteIp + "] - nhân viên " + staff.getUsername() + " đăng nhập.");
                break;
        }
        return true;
    }

    private boolean reject(String requestedId, String reason) {
        server.log("[SECURITY] Từ chối kết nối từ " + requestedId + " (" + remoteIp + "): " + reason);
        sendMessage(CommandType.AUTH_FAIL + "|" + reason);
        // Ghi vào security_logs (không có khóa ngoại) nên thiết bị lạ chưa đăng ký vẫn ghi được
        DatabaseManager.saveSecurityLog(requestedId, remoteIp, "AUTH_FAIL", reason);
        return false;
    }

    /** AUTH_ACK|LỜI_CHÀO|MÃ_NV|TÊN_NV|VAI_TRÒ_NV */
    private String buildStaffAck(String greeting) {
        return CommandType.AUTH_ACK + "|" + greeting + "|" + staff.getStaffId() + "|" + enc(staff.getFullName()) + "|" + enc(staff.getRole());
    }

    private void handlePacket(NetworkPacket packet) {
        switch (packet.getType()) {
            case PING:
                // Heartbeat: trả PONG để client biết Server còn sống (không ghi log cho đỡ rối)
                sendMessage(CommandType.PONG.name());
                break;
            case RING:
                handleRing(packet);
                break;
            case ORDER:
                handleOrder(packet);
                break;
            case CHAT_TO_RECEPTION:
                handleChatToReception(packet);
                break;
            case CHAT_TO_TABLE:
                handleChatToTable(packet);
                break;
            case GET_HISTORY:
                handleGetHistory();
                break;
            case GET_MENU:
                handleGetMenu();
                break;
            case ORDER_STATUS_UPDATE:
                // ORDER_STATUS_UPDATE|MÃ_ĐƠN|BÀN|TRẠNG_THÁI_MỚI (Lễ tân: đã phục vụ / hủy đơn)
                changeOrderStatus(packet.getInt(1, 0), packet.getText(3), false); // Lễ tân / Phục vụ
                break;
            case KITCHEN_STATUS:
                // KITCHEN_STATUS|MÃ_ĐƠN|TRẠNG_THÁI_MỚI (Bếp: đang nấu / đã xong)
                changeOrderStatus(packet.getInt(1, 0), packet.getText(2), true);
                break;
            case GET_KITCHEN_ORDERS:
                handleGetKitchenOrders(packet);
                break;
            case GET_TABLES:
                handleGetTables();
                break;
            case GET_TABLE_DETAIL:
                handleGetTableDetail(packet);
                break;
            case SAVE_SESSION:
                handleSaveSession(packet);
                break;
            case CHECKOUT:
                handleCheckout(packet);
                break;
            case SET_TABLE_STATUS:
                handleSetTableStatus(packet);
                break;
            case MOVE_TABLE:
                handleMoveTable(packet);
                break;
            case CANCEL_SESSION:
                handleCancelSession(packet);
                break;
            case GET_RESERVATIONS:
                handleGetReservations(packet.getPart(1));
                break;
            case SAVE_RESERVATION:
                handleSaveReservation(packet);
                break;
            case RESERVATION_STATUS:
                handleReservationStatus(packet);
                break;
            case CHECK_IN:
                handleCheckIn(packet);
                break;
            case GET_MY_ORDERS:
                handleGetMyOrders();
                break;
            case GET_CHAT:
                handleGetChat(packet.getPart(1), packet.getPart(2));
                break;
            case GET_CONVERSATIONS:
                handleGetConversations();
                break;
            case GET_MY_TABLES:
                handleGetMyTables();
                break;
            case GET_BILL:
                // Tablet xem hóa đơn của chính bàn mình; Lễ tân xem hóa đơn của bàn chỉ định (GET_BILL|BÀN)
                sendMessage(RestaurantService.buildBillPreview(role == DeviceRole.TABLET ? deviceId : packet.getPart(1)));
                break;
            case PAY_REQUEST:
                handlePayRequest(packet);
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ Tablet

    private void handleRing(NetworkPacket packet) {
        // RING|BÀN|LÝ_DO -> Lễ tân nhận RING|BÀN|LÝ_DO|NHÂN_VIÊN_PHỤC_VỤ_CỦA_BÀN
        String reason = packet.getPart(2);
        if (reason.isEmpty()) reason = "Gọi nhân viên phục vụ tại bàn";
        TableSession s = SessionDao.getOpenSession(deviceId);
        String waiter = (s == null) ? "" : s.getWaiterName();
        server.log("[BELL] " + deviceId + ": " + reason + (waiter.isEmpty() ? " -> Lễ tân" : " -> phục vụ " + waiter + " + Lễ tân"));
        DatabaseManager.saveChatLog(deviceId, customerLabel(s), "STAFF", "RING", reason);
        String ring = "RING|" + deviceId + "|" + reason + "|" + enc(waiter);
        server.broadcastToReception(ring);
        boolean waiterGot = s != null && server.sendToWaiter(s.getWaiterId(), ring, null);
        if (waiterGot) {
            sendMessage(NetworkPacket.buildServerAck("Đã báo nhân viên phục vụ " + waiter + ": " + reason));
        } else if (server.getReceptionSessions().isEmpty()) {
            sendMessage(NetworkPacket.buildServerAck("Nhân viên chưa trực tuyến. Yêu cầu [" + reason + "] đã được lưu, nhân viên sẽ thấy khi vào ca."));
        } else {
            sendMessage(NetworkPacket.buildServerAck("Đã chuyển yêu cầu [" + reason + "] tới quầy Lễ tân!"));
        }
    }

    private void handleOrder(NetworkPacket packet) {
        // ORDER|BÀN|GHI_CHÚ_ĐƠN|DÒNG;DÒNG... (bàn lấy theo thiết bị đã đăng nhập, không tin tham số trong gói)
        RestaurantService.Result<Order> r = RestaurantService.placeOrder(deviceId, packet.getText(2),
                NetworkPacket.parseOrderLines(packet.getPart(3)));
        if (!r.isOk()) {
            server.log("[ORDER] Từ chối đơn của " + deviceId + ": " + r.error);
            sendMessage("ORDER_REJECTED|" + enc(r.error));
            return;
        }
        Order o = r.value;
        server.log("[ORDER] Đơn " + o.getOrderNoText() + " (mã " + o.getOrderId() + ") của " + o.getTableName()
                + ": " + o.getFormattedTotal() + " -> chuyển Bếp và Lễ tân");
        DatabaseManager.saveChatOrBellLog(deviceId, "POS_Server", "ORDER",
                o.getOrderNoText() + ": " + o.getItemsSummary() + " (" + o.getTotalAmount() + " VNĐ)");
        server.broadcastToReception(RestaurantService.buildReceptionOrder("NEW_ORDER", o));
        server.broadcastToKitchen(RestaurantService.buildKitchenOrder(o));
        TableSession orderSession = SessionDao.getOpenSession(deviceId);
        if (orderSession != null) server.sendToWaiter(orderSession.getWaiterId(), RestaurantService.buildReceptionOrder("NEW_ORDER", o), null);
        server.broadcastTableInfo(deviceId); // đơn đầu tiên tự mở bàn
        // ORDER_ACCEPTED|MÃ_ĐƠN|STT|TỔNG_TIỀN|TÓM_TẮT|BẾP_ĐANG_TRỰC(1/0)
        sendMessage("ORDER_ACCEPTED|" + o.getOrderId() + "|" + o.getOrderNo() + "|" + o.getTotalAmount()
                + "|" + enc(o.getItemsSummary()) + "|" + (server.getKitchenSessions().isEmpty() ? 0 : 1));
    }

    private void handleChatToReception(NetworkPacket packet) {
        // CHAT_TO_RECEPTION|BÀN|NỘI_DUNG: khách nhắn nhân viên. Tin tới nhân viên phục vụ được giao bàn này
        // (nếu Lễ tân đã phân công) và tới mọi quầy Lễ tân (để điều phối, theo dõi).
        String chatMsg = packet.getPart(2);
        if (chatMsg.trim().isEmpty()) return;
        TableSession s = SessionDao.getOpenSession(deviceId);
        String sender = customerLabel(s);
        server.log("[IM-CHAT] " + sender + " -> " + (s != null && s.getWaiterId() > 0 ? "phục vụ " + s.getWaiterName() + " + " : "") + "Lễ tân: \"" + chatMsg + "\"");
        DatabaseManager.saveChatLog(deviceId, sender, "STAFF", "CHAT", chatMsg);
        String event = chatEvent(deviceId, "IN", sender, chatMsg);
        server.broadcastToReception(event);
        boolean waiterGot = s != null && server.sendToWaiter(s.getWaiterId(), event, null);
        // Báo trạng thái gửi cho khách: DELIVERED = đã tới nhân viên (kèm tên ai nhận), STORED = chưa ai trực tuyến, chỉ lưu MySQL
        if (waiterGot) {
            sendMessage("CHAT_ACK|DELIVERED|" + enc(s.getWaiterName()));
        } else {
            sendMessage("CHAT_ACK|" + (server.getReceptionSessions().isEmpty() ? "STORED" : "DELIVERED|" + enc("Lễ tân")));
        }
    }

    /** "Khách Bàn 1 (Chị Lan)" - tên hiển thị của khách trong nhật ký chat */
    private String customerLabel(TableSession s) {
        String name = TableDao.defaultTableName(deviceId);
        DiningTable t = TableDao.getTable(deviceId);
        if (t != null) name = t.getTableName();
        return "Khách " + name + (s != null && !s.getCustomerName().isEmpty() ? " (" + s.getCustomerName() + ")" : "");
    }

    /** CHAT_EVENT|BÀN|IN(khách gửi)/OUT(nhân viên gửi)|TÊN_NGƯỜI_GỬI|GIỜ|NỘI_DUNG cho Lễ tân và Phục vụ */
    private static String chatEvent(String tableId, String direction, String senderName, String text) {
        return "CHAT_EVENT|" + tableId + "|" + direction + "|" + enc(senderName) + "|" + new SimpleDateFormat("HH:mm").format(new java.util.Date())
                + "|" + enc(text);
    }

    private void handleGetMenu() {
        // GET_MENU -> MENU_BEGIN|SỐ_DÒNG, các dòng MENU_ITEM và COMBO_ITEM, MENU_END
        // Nhờ vậy Tablet ở máy khác không cần kết nối MySQL (chỉ Server truy cập Database)
        List<MenuItem> menu = DatabaseManager.getAllMenuItems();
        List<Combo> combos = ComboDao.getAllCombos(true);
        sendMessage("MENU_BEGIN|" + (menu.size() + combos.size()));
        for (MenuItem item : menu) {
            sendMessage(NetworkPacket.buildMenuItem(item.getItemId(), item.getCategory(), item.getItemName(), item.getPrice(), item.getImageData()));
        }
        for (Combo c : combos) {
            sendMessage(NetworkPacket.buildComboItem(c.getComboId(), c.getComboName(), c.getPrice(), c.getDescription(), c.getItemsSummary(), c.getImageData()));
        }
        sendMessage("MENU_END");
        server.log("[MENU-SYNC] Đã gửi " + menu.size() + " món và " + combos.size() + " combo cho " + deviceId);
    }

    // ------------------------------------------------------------------ dùng chung

    private void handleGetHistory() {
        // GET_HISTORY -> nhiều dòng HISTORY rồi HISTORY_END|SO_DONG
        // Dùng deviceId của phiên đã xác thực (không tin tham số trong gói) nên bàn này không xem được tin của bàn khác
        // Tablet chỉ thấy tin nhắn của lượt khách hiện tại (khách mới không thấy tin của khách trước)
        List<ChatMessage> history;
        if (role == DeviceRole.TABLET) {
            TableSession s = SessionDao.getOpenSession(deviceId);
            history = (s == null || s.getOpenedAt() == null) ? new java.util.ArrayList<>()
                    : DatabaseManager.getTableChatSince(deviceId, s.getOpenedAt(), false, ProtocolConstants.CHAT_HISTORY_LIMIT);
        } else {
            history = DatabaseManager.getRecentChatHistory(ProtocolConstants.CHAT_HISTORY_LIMIT);
        }
        SimpleDateFormat fmt = new SimpleDateFormat("dd/MM HH:mm");
        for (ChatMessage m : history) {
            String time = (m.getSentAt() != null) ? fmt.format(m.getSentAt()) : "";
            sendMessage(NetworkPacket.buildHistoryItem(time, m.getSender(), m.getReceiver(), m.getMessageType(), m.getContent(), m.getSenderName()));
        }
        sendMessage("HISTORY_END|" + history.size());
    }

    /**
     * Đổi trạng thái đơn, kiểm tra đúng quy trình:
     * Bếp: CHỜ BẾP -> ĐANG NẤU -> ĐÃ XONG. Lễ tân: -> ĐÃ PHỤC VỤ hoặc ĐÃ HỦY.
     * Đơn đã phục vụ / đã hủy, hoặc bàn đã thanh toán thì không đổi được nữa.
     */
    private void changeOrderStatus(int orderId, String newStatus, boolean byKitchen) {
        Order o = OrderDao.getOrder(orderId);
        if (o == null) {
            sendMessage(NetworkPacket.buildServerAck("Không tìm thấy đơn mã " + orderId + "."));
            return;
        }
        String cur = o.getOrderStatus();
        String error = null;
        if (Order.STATUS_SERVED.equals(cur) || Order.STATUS_CANCELLED.equals(cur)) {
            error = "Đơn " + o.getOrderNoText() + " đã ở trạng thái cuối (" + cur + "), không đổi được nữa.";
        } else if (byKitchen) {
            boolean ok = (Order.STATUS_COOKING.equals(newStatus) && Order.STATUS_WAITING.equals(cur))
                    || (Order.STATUS_DONE.equals(newStatus) && !Order.STATUS_DONE.equals(cur));
            if (!ok) error = "Bếp không thể chuyển đơn " + o.getOrderNoText() + " từ " + cur + " sang " + newStatus + ".";
        } else if (role == DeviceRole.WAITER) {
            TableSession ws = SessionDao.getSession(o.getSessionId());
            if (!Order.STATUS_SERVED.equals(newStatus)) {
                error = "Nhân viên phục vụ chỉ được đánh dấu đơn là " + Order.STATUS_SERVED + " (hủy đơn do Lễ tân làm).";
            } else if (ws == null || ws.getWaiterId() != getStaffId()) {
                error = "Bàn " + o.getTableName() + " không do bạn phụ trách.";
            }
        } else if (!Order.STATUS_SERVED.equals(newStatus) && !Order.STATUS_CANCELLED.equals(newStatus)) {
            error = "Lễ tân chỉ được đánh dấu đơn là " + Order.STATUS_SERVED + " hoặc " + Order.STATUS_CANCELLED + ".";
        }
        if (error == null) {
            TableSession s = SessionDao.getSession(o.getSessionId());
            if (s != null && !TableSession.STATUS_OPEN.equals(s.getStatus())) {
                error = "Bàn của đơn " + o.getOrderNoText() + " đã thanh toán, không đổi trạng thái được.";
            }
        }
        if (error != null) {
            sendMessage(NetworkPacket.buildServerAck(error));
            return;
        }

        OrderDao.updateStatus(orderId, newStatus);
        o.setOrderStatus(newStatus);
        server.log("[ORDER] " + (byKitchen ? "Bếp" : role == DeviceRole.WAITER ? "Phục vụ" : "Lễ tân") + " " + deviceId + " (" + getStaffName() + ") chuyển đơn "
                + o.getOrderNoText() + " của " + o.getTableName() + " -> " + newStatus);
        // ORDER_UPDATED|MÃ_ĐƠN|TRẠNG_THÁI|STT|BÀN cho mọi quầy Lễ tân và mọi màn hình Bếp
        String update = "ORDER_UPDATED|" + orderId + "|" + newStatus + "|" + o.getOrderNo() + "|" + o.getTableId();
        server.broadcastToReception(update);
        server.broadcastToKitchen(update);
        server.broadcastToWaiters(update); // máy phục vụ tự lọc theo các bàn mình phụ trách (vd. bếp nấu xong -> mang ra)
        server.sendToTablet(o.getTableId(), RestaurantService.buildTabletOrderStatus(o));
        if (Order.STATUS_CANCELLED.equals(newStatus)) {
            server.broadcastTableInfo(o.getTableId());
        }
    }

    // ------------------------------------------------------------------ Lễ tân

    private void handleChatToTable(NetworkPacket packet) {
        // CHAT_TO_TABLE|BÀN_NHẬN|NỘI_DUNG (Lễ tân, hoặc Phục vụ nhắn bàn mình phụ trách)
        String targetTable = packet.getPart(1);
        String reply = packet.getPart(2);
        if (reply.trim().isEmpty()) return;
        DiningTable t = TableDao.getTable(targetTable);
        if (t == null) {
            sendMessage(NetworkPacket.buildServerAck("Không tìm thấy bàn " + targetTable + "."));
            return;
        }
        TableSession s = SessionDao.getOpenSession(targetTable);
        if (role == DeviceRole.WAITER && (s == null || s.getWaiterId() != getStaffId())) {
            sendMessage(NetworkPacket.buildServerAck(t.getTableName() + " không do bạn phụ trách, không nhắn được."));
            return;
        }
        String sender = (role == DeviceRole.WAITER ? "Phục vụ " : "Lễ tân ") + getStaffName();
        server.log("[IM-REPLY] " + sender + " -> " + targetTable + ": \"" + reply + "\"");
        DatabaseManager.saveChatLog(deviceId, sender, targetTable, "CHAT", reply);
        // Mọi quầy Lễ tân + phục vụ của bàn đều thấy câu trả lời (tránh 2 người cùng trả lời 1 bàn)
        String event = chatEvent(targetTable, "OUT", sender, reply);
        server.broadcastToReceptionExcept(event, this);
        if (s != null) server.sendToWaiter(s.getWaiterId(), event, this);
        ClientSession targetSession = server.getTabletSession(targetTable);
        if (targetSession != null && targetSession.isConnected()) {
            targetSession.sendMessage("CHAT_FROM_STAFF|" + enc(sender) + "|" + enc(reply));
        } else {
            sendMessage(NetworkPacket.buildServerAck(t.getTableName() + " hiện không kết nối. Tin nhắn đã được lưu, bàn sẽ thấy khi kết nối lại."));
        }
    }

    /**
     * GET_CHAT|BÀN|PHẠM_VI -> CHAT_HISTORY_BEGIN|BÀN|PHẠM_VI, các dòng CHAT_ITEM, CHAT_HISTORY_END|BÀN|SỐ_DÒNG
     * CHAT_ITEM|BÀN|IN/OUT|TÊN_NGƯỜI_GỬI|dd/MM HH:mm|LOẠI(CHAT/RING)|NỘI_DUNG
     * Phạm vi: SESSION = lượt khách hiện tại (bàn trống thì lấy hôm nay), TODAY = hôm nay, WEEK = 7 ngày gần nhất.
     */
    private void handleGetChat(String tableId, String scope) {
        DiningTable t = TableDao.getTable(tableId);
        if (t == null) {
            sendMessage(NetworkPacket.buildServerAck("Không tìm thấy bàn " + tableId + "."));
            return;
        }
        TableSession s = SessionDao.getOpenSession(tableId);
        if (role == DeviceRole.WAITER && (s == null || s.getWaiterId() != getStaffId())) {
            sendMessage(NetworkPacket.buildServerAck(t.getTableName() + " không do bạn phụ trách."));
            return;
        }
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        java.sql.Timestamp since;
        if ("WEEK".equalsIgnoreCase(scope)) {
            c.add(java.util.Calendar.DAY_OF_MONTH, -6);
            since = new java.sql.Timestamp(c.getTimeInMillis());
        } else if ("TODAY".equalsIgnoreCase(scope) || s == null || s.getOpenedAt() == null) {
            since = new java.sql.Timestamp(c.getTimeInMillis());
        } else {
            since = s.getOpenedAt();
        }
        List<ChatMessage> list = DatabaseManager.getTableChatSince(tableId, since, true, 300);
        SimpleDateFormat fmt = new SimpleDateFormat("dd/MM HH:mm");
        sendMessage("CHAT_HISTORY_BEGIN|" + tableId + "|" + scope);
        for (ChatMessage m : list) {
            boolean in = tableId.equals(m.getSender());
            String name = m.getSenderName().isEmpty() ? (in ? "Khách " + t.getTableName() : "Nhân viên") : m.getSenderName();
            sendMessage("CHAT_ITEM|" + tableId + "|" + (in ? "IN" : "OUT") + "|" + enc(name) + "|"
                    + (m.getSentAt() == null ? "" : fmt.format(m.getSentAt())) + "|" + m.getMessageType() + "|" + enc(m.getContent()));
        }
        sendMessage("CHAT_HISTORY_END|" + tableId + "|" + list.size());
    }

    /** GET_CONVERSATIONS -> CONV|BÀN|GIỜ_TIN_CUỐI|TIN_CUỐI|SỐ_TIN_HÔM_NAY ... CONV_END */
    private void handleGetConversations() {
        for (String[] c : DatabaseManager.getConversations()) {
            sendMessage("CONV|" + c[0] + "|" + c[1] + "|" + enc(c[2]) + "|" + c[3]);
        }
        sendMessage("CONV_END");
    }

    /**
     * Máy phục vụ: MY_TABLES_BEGIN, TABLE_INFO của các bàn đang được giao cho nhân viên này,
     * WAITER_ORDER (cùng định dạng NEW_ORDER) cho các đơn chưa mang ra, MY_TABLES_END
     */
    private void handleGetMyTables() {
        List<DiningTable> tables = TableDao.getAllTables();
        Map<String, Reservation> upcoming = RestaurantService.upcomingReservations();
        sendMessage("MY_TABLES_BEGIN");
        java.util.List<TableSession> mine = new java.util.ArrayList<>();
        for (DiningTable t : tables) {
            TableSession s = SessionDao.getOpenSession(t.getTableId());
            if (s == null || s.getWaiterId() != getStaffId()) continue;
            mine.add(s);
            sendMessage(RestaurantService.buildTableInfo(t, s, server.isTabletOnline(t.getTableId()), upcoming.get(t.getTableId())));
        }
        for (TableSession s : mine) {
            for (Order o : OrderDao.getOrdersBySession(s.getSessionId())) {
                if (Order.STATUS_SERVED.equals(o.getOrderStatus()) || Order.STATUS_CANCELLED.equals(o.getOrderStatus())) continue;
                sendMessage(RestaurantService.buildReceptionOrder("WAITER_ORDER", o));
            }
        }
        sendMessage("MY_TABLES_END|" + mine.size());
    }

    private void handleGetTables() {
        // TABLES_BEGIN|SỐ_BÀN, các dòng TABLE_INFO, các dòng WAITER|MÃ|TÊN, TABLES_END
        List<DiningTable> tables = TableDao.getAllTables();
        Map<String, Reservation> upcoming = RestaurantService.upcomingReservations();
        sendMessage("TABLES_BEGIN|" + tables.size());
        for (DiningTable t : tables) {
            sendMessage(RestaurantService.buildTableInfo(t, SessionDao.getOpenSession(t.getTableId()),
                    server.isTabletOnline(t.getTableId()), upcoming.get(t.getTableId())));
        }
        // WAITER|MÃ|TÊN|ĐANG_TRỰC(1/0)|SỐ_BÀN_ĐANG_PHỤ_TRÁCH: để Lễ tân chia bàn đều cho các phục vụ
        Map<Integer, Integer> load = new java.util.HashMap<>();
        for (DiningTable t : tables) {
            TableSession s = SessionDao.getOpenSession(t.getTableId());
            if (s != null && s.getWaiterId() > 0) load.merge(s.getWaiterId(), 1, Integer::sum);
        }
        for (Staff w : StaffDao.getActiveWaiters()) {
            sendMessage("WAITER|" + w.getStaffId() + "|" + enc(w.getFullName()) + "|" + (server.isWaiterOnline(w.getStaffId()) ? 1 : 0)
                    + "|" + load.getOrDefault(w.getStaffId(), 0));
        }
        sendMessage("TABLES_END");
    }

    private void handleGetTableDetail(NetworkPacket packet) {
        String tableId = packet.getPart(1);
        DiningTable t = TableDao.getTable(tableId);
        if (t == null) {
            sendMessage(NetworkPacket.buildServerAck("Không tìm thấy bàn " + tableId + "."));
            return;
        }
        TableSession s = SessionDao.getOpenSession(tableId);
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm dd/MM");
        // TABLE_SESSION|BÀN|MÃ_PHIÊN|TÊN_KHÁCH|SĐT|SỐ_KHÁCH|MÃ_PHỤC_VỤ|TÊN_PHỤC_VỤ|GHI_CHÚ|TRẠNG_THÁI_BÀN|GIỜ_VÀO|TẠM_TÍNH|TIỀN_CỌC|YÊU_CẦU_TT
        if (s == null) {
            sendMessage("TABLE_SESSION|" + tableId + "|0||||0|||" + enc(t.getStatus()) + "||0|0|");
        } else {
            sendMessage("TABLE_SESSION|" + tableId + "|" + s.getSessionId() + "|" + enc(s.getCustomerName()) + "|" + enc(s.getCustomerPhone())
                    + "|" + s.getGuestCount() + "|" + s.getWaiterId() + "|" + enc(s.getWaiterName()) + "|" + enc(s.getNote())
                    + "|" + enc(t.getStatus()) + "|" + (s.getOpenedAt() == null ? "" : fmt.format(s.getOpenedAt()))
                    + "|" + SessionDao.getSubtotal(s.getSessionId()) + "|" + s.getDeposit() + "|" + enc(s.getPayRequest()));
            // TABLE_ORDER|MÃ_ĐƠN|STT|GIỜ|TÓM_TẮT|TỔNG_TIỀN|TRẠNG_THÁI|GHI_CHÚ
            for (Order o : OrderDao.getOrdersBySession(s.getSessionId())) {
                sendMessage("TABLE_ORDER|" + o.getOrderId() + "|" + o.getOrderNo() + "|" + o.getShortTime() + "|" + enc(o.getItemsSummary())
                        + "|" + o.getTotalAmount() + "|" + enc(o.getOrderStatus()) + "|" + enc(o.getNote()));
            }
        }
        // TABLE_CHAT|GIỜ|NGƯỜI_GỬI|NGƯỜI_NHẬN|LOẠI|NỘI_DUNG
        SimpleDateFormat chatFmt = new SimpleDateFormat("dd/MM HH:mm");
        List<ChatMessage> chat = (s == null || s.getOpenedAt() == null) ? new java.util.ArrayList<>()
                : DatabaseManager.getTableChatSince(tableId, s.getOpenedAt(), true, ProtocolConstants.CHAT_HISTORY_LIMIT);
        for (ChatMessage m : chat) {
            String time = (m.getSentAt() != null) ? chatFmt.format(m.getSentAt()) : "";
            sendMessage("TABLE_CHAT|" + time + "|" + m.getSender() + "|" + m.getReceiver() + "|" + m.getMessageType() + "|" + enc(m.getContent())
                    + "|" + enc(m.getSenderName()));
        }
        sendMessage("TABLE_DETAIL_END|" + tableId);
    }

    private void handleSaveSession(NetworkPacket packet) {
        // SAVE_SESSION|BÀN|TÊN_KHÁCH|SĐT|SỐ_KHÁCH|MÃ_PHỤC_VỤ|GHI_CHÚ
        String tableId = packet.getPart(1);
        String name = packet.getText(2).trim();
        String phone = packet.getText(3).trim();
        int guests = packet.getInt(4, -1);
        int waiterId = packet.getInt(5, 0);
        String note = packet.getText(6).trim();

        String error = null;
        DiningTable table = TableDao.getTable(tableId);
        if (table == null) error = "Không tìm thấy bàn " + tableId + ".";
        else if (name.isEmpty()) error = "Vui lòng nhập tên khách (để biết ai là người dùng bàn).";
        else if (name.length() > 80) error = "Tên khách tối đa 80 ký tự.";
        else if (!phone.isEmpty() && !phone.matches("[0-9 +.\\-]{6,20}")) error = "Số điện thoại không hợp lệ.";
        else if (guests < 1) error = "Số khách phải từ 1 trở lên.";
        else if (guests > table.getCapacity()) {
            error = table.getTableName() + " chỉ có " + table.getCapacity() + " ghế, không đủ cho " + guests
                    + " khách. Hãy chọn bàn lớn hơn (hoặc chuyển bàn).";
        }
        else if (note.length() > ProtocolConstants.MAX_NOTE_LENGTH) error = "Ghi chú tối đa " + ProtocolConstants.MAX_NOTE_LENGTH + " ký tự.";
        else if (waiterId > 0) {
            Staff w = StaffDao.getStaff(waiterId);
            if (w == null || !w.isActive() || !Staff.ROLE_WAITER.equals(w.getRole())) error = "Nhân viên phục vụ không hợp lệ.";
        }
        if (error == null && SessionDao.getOpenSession(tableId) == null
                && DiningTable.STATUS_CLEANING.equals(TableDao.getTable(tableId).getStatus())) {
            error = "Bàn chưa được dọn sau lượt khách trước. Bấm \"Đã dọn bàn xong\" rồi mới mở bàn.";
        }
        if (error != null) {
            sendMessage("SESSION_SAVE_FAIL|" + tableId + "|" + enc(error));
            return;
        }

        TableSession s = SessionDao.saveSessionInfo(tableId, name, phone, guests, waiterId, note);
        if (s == null) {
            sendMessage("SESSION_SAVE_FAIL|" + tableId + "|" + enc("Không lưu được vào database."));
            return;
        }
        server.log("[TABLE] " + getStaffName() + " lưu thông tin " + tableId + ": khách " + (name.isEmpty() ? "(không tên)" : name)
                + ", " + guests + " người" + (s.getWaiterName().isEmpty() ? "" : ", phục vụ " + s.getWaiterName()));
        sendMessage("SESSION_SAVED|" + tableId + "|" + s.getSessionId());
        server.broadcastTableInfo(tableId);
        server.sendToTablet(tableId, RestaurantService.buildTabletSessionInfo(s));
    }

    private void handleCheckout(NetworkPacket packet) {
        // CHECKOUT|BÀN|GIẢM_GIÁ|HÌNH_THỨC
        String tableId = packet.getPart(1);
        long discount = packet.getLong(2, -1);
        String method = packet.getText(3);
        RestaurantService.Result<String> r = RestaurantService.checkout(tableId, discount, method, staff == null ? 0 : staff.getStaffId());
        if (!r.isOk()) {
            sendMessage("CHECKOUT_FAIL|" + tableId + "|" + enc(r.error));
            return;
        }
        String bill = r.value;
        String[] f = NetworkPacket.split(bill);
        server.log("[CHECKOUT] " + getStaffName() + " thanh toán " + tableId + ": " + f[6] + " VNĐ (" + method + ")");
        sendMessage(bill);
        server.broadcastTableInfo(tableId);
        server.sendToTablet(tableId, "SESSION_CLOSED|" + f[6]);
        // Lượt đặt / bàn thay đổi: các quầy khác cập nhật sơ đồ
        server.notifyRevenueChanged();
    }

    private void handleSetTableStatus(NetworkPacket packet) {
        // SET_TABLE_STATUS|BÀN|TRẠNG_THÁI (TRỐNG / ĐÃ ĐẶT / CẦN DỌN; ĐANG PHỤC VỤ chỉ có khi mở bàn)
        String tableId = packet.getPart(1);
        String status = packet.getText(2);
        boolean validStatus = DiningTable.STATUS_EMPTY.equals(status) || DiningTable.STATUS_RESERVED.equals(status)
                || DiningTable.STATUS_CLEANING.equals(status);
        if (!validStatus) {
            sendMessage(NetworkPacket.buildServerAck("Trạng thái bàn không hợp lệ: " + status));
            return;
        }
        if (TableDao.getTable(tableId) == null) {
            sendMessage(NetworkPacket.buildServerAck("Không tìm thấy bàn " + tableId + "."));
            return;
        }
        if (SessionDao.getOpenSession(tableId) != null) {
            sendMessage(NetworkPacket.buildServerAck("Bàn đang có khách, hãy thanh toán trước khi đổi trạng thái."));
            return;
        }
        TableDao.updateStatus(tableId, status);
        server.log("[TABLE] " + getStaffName() + " đổi trạng thái " + tableId + " -> " + status);
        sendMessage(NetworkPacket.buildServerAck("Đã chuyển " + tableId + " sang " + status + "."));
        server.broadcastTableInfo(tableId);
    }

    private void handleCancelSession(NetworkPacket packet) {
        // CANCEL_SESSION|BÀN|LÝ_DO: khách mở bàn rồi về khi chưa gọi món. Bàn đã có món thì phải thanh toán.
        String tableId = packet.getPart(1);
        TableSession s = SessionDao.getOpenSession(tableId);
        String error = SessionDao.cancelSession(tableId, packet.getText(2), staff == null ? 0 : staff.getStaffId());
        if (error != null) {
            sendMessage("CANCEL_FAIL|" + tableId + "|" + enc(error));
            return;
        }
        long deposit = s == null ? 0 : s.getDeposit();
        server.log("[TABLE] " + getStaffName() + " hủy bàn " + tableId + " (khách về, chưa gọi món)"
                + (deposit > 0 ? ", giữ tiền cọc " + deposit + " VNĐ" : ""));
        sendMessage("SESSION_CANCELLED|" + tableId + "|" + deposit);
        server.broadcastTableInfo(tableId);
        server.sendToTablet(tableId, "SESSION_CANCELLED");
        if (deposit > 0) server.notifyRevenueChanged();
    }

    private void handleMoveTable(NetworkPacket packet) {
        // MOVE_TABLE|BÀN_HIỆN_TẠI|BÀN_MỚI
        String from = packet.getPart(1);
        String to = packet.getPart(2);
        DiningTable target = TableDao.getTable(to);
        if (target == null) {
            sendMessage("MOVE_FAIL|" + from + "|" + enc("Không tìm thấy bàn " + to + "."));
            return;
        }
        if (DiningTable.STATUS_CLEANING.equals(target.getStatus())) {
            sendMessage("MOVE_FAIL|" + from + "|" + enc(target.getTableName() + " chưa được dọn, hãy chọn bàn khác."));
            return;
        }
        TableSession moving = SessionDao.getOpenSession(from);
        if (moving != null && moving.getGuestCount() > target.getCapacity()) {
            sendMessage("MOVE_FAIL|" + from + "|" + enc(target.getTableName() + " chỉ có " + target.getCapacity()
                    + " ghế, không đủ cho " + moving.getGuestCount() + " khách."));
            return;
        }
        String error = SessionDao.moveSession(from, to);
        if (error != null) {
            sendMessage("MOVE_FAIL|" + from + "|" + enc(error));
            return;
        }
        server.log("[TABLE] " + getStaffName() + " chuyển khách từ " + from + " sang " + to);
        sendMessage("TABLE_MOVED|" + from + "|" + to);
        server.broadcastTableInfo(from);
        server.broadcastTableInfo(to);
        server.sendToTablet(from, "SESSION_MOVED|" + to);
        server.sendToTablet(to, RestaurantService.buildTabletSessionInfo(SessionDao.getOpenSession(to)));
        server.sendToTablet(to, "ORDERS_CHANGED");
    }

    private void handleGetReservations(String day) {
        // GET_RESERVATIONS|yyyy-MM-dd -> RESERVATIONS_BEGIN|NGÀY, các dòng RESERVATION, RESERVATIONS_END|NGÀY
        Date date;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd");
            f.setLenient(false);
            date = new Date(f.parse(day).getTime());
        } catch (ParseException e) {
            date = new Date(System.currentTimeMillis());
        }
        String d = date.toString();
        List<Reservation> list = ReservationDao.getByDate(date);
        sendMessage("RESERVATIONS_BEGIN|" + d + "|" + list.size());
        for (Reservation r : list) {
            sendMessage(RestaurantService.buildReservation(r));
        }
        sendMessage("RESERVATIONS_END|" + d);
    }

    private void handleSaveReservation(NetworkPacket packet) {
        // SAVE_RESERVATION|MÃ|TÊN|SĐT|SỐ_KHÁCH|NGÀY_GIỜ|BÀN|KHU_VỰC|TIỀN_CỌC|GHI_CHÚ
        RestaurantService.Result<Reservation> r = RestaurantService.saveReservation(packet.getInt(1, 0), packet.getText(2),
                packet.getText(3), packet.getInt(4, 0), packet.getText(5), packet.getPart(6), packet.getText(7),
                packet.getLong(8, -1), packet.getText(9), staff == null ? 0 : staff.getStaffId());
        if (!r.isOk()) {
            sendMessage("RESERVATION_FAIL|" + enc(r.error));
            return;
        }
        Reservation res = r.value;
        server.log("[RESERVATION] " + getStaffName() + " lưu lượt đặt #" + res.getReservationId() + ": " + res.getCustomerName()
                + ", " + res.getGuestCount() + " khách, " + res.getDateTimeText() + (res.getTableId() == null ? "" : ", " + res.getTableName()));
        sendMessage("RESERVATION_SAVED|" + res.getReservationId());
        reservationsChanged(res);
    }

    private void handleReservationStatus(NetworkPacket packet) {
        // RESERVATION_STATUS|MÃ|TRẠNG_THÁI (ĐÃ HỦY / KHÔNG ĐẾN)
        int id = packet.getInt(1, 0);
        String status = packet.getText(2);
        if (!Reservation.STATUS_CANCELLED.equals(status) && !Reservation.STATUS_NO_SHOW.equals(status)) {
            sendMessage("RESERVATION_FAIL|" + enc("Trạng thái không hợp lệ: " + status));
            return;
        }
        Reservation res = ReservationDao.get(id);
        if (res != null && Reservation.STATUS_NO_SHOW.equals(status) && System.currentTimeMillis() < res.getReservedAt().getTime()) {
            sendMessage("RESERVATION_FAIL|" + enc("Chưa tới giờ hẹn (" + res.getTimeText() + ") nên chưa thể đánh dấu Không đến."
                    + " Nếu khách báo không tới, hãy chọn \"Khách hủy\"."));
            return;
        }
        if (res == null || !ReservationDao.setStatus(id, status, 0)) {
            sendMessage("RESERVATION_FAIL|" + enc("Lượt đặt không còn ở trạng thái ĐÃ ĐẶT."));
            return;
        }
        server.log("[RESERVATION] " + getStaffName() + " chuyển lượt đặt #" + id + " (" + res.getCustomerName() + ") -> " + status);
        sendMessage("RESERVATION_SAVED|" + id);
        reservationsChanged(res);
    }

    private void handleCheckIn(NetworkPacket packet) {
        // CHECK_IN|MÃ_LƯỢT_ĐẶT|BÀN(rỗng = bàn đã đặt)|MÃ_PHỤC_VỤ
        int id = packet.getInt(1, 0);
        int waiterId = packet.getInt(3, 0);
        if (waiterId > 0) {
            Staff w = StaffDao.getStaff(waiterId);
            if (w == null || !w.isActive() || !Staff.ROLE_WAITER.equals(w.getRole())) {
                sendMessage("RESERVATION_FAIL|" + enc("Nhân viên phục vụ không hợp lệ."));
                return;
            }
        }
        RestaurantService.Result<TableSession> r = RestaurantService.checkIn(id, packet.getPart(2), waiterId);
        if (!r.isOk()) {
            sendMessage("RESERVATION_FAIL|" + enc(r.error));
            return;
        }
        TableSession s = r.value;
        server.log("[RESERVATION] Khách đặt trước " + s.getCustomerName() + " đã đến, nhận " + s.getTableId());
        sendMessage("CHECKED_IN|" + s.getTableId() + "|" + s.getSessionId());
        Reservation res = ReservationDao.get(id);
        if (res != null) reservationsChanged(res);
        server.broadcastTableInfo(s.getTableId());
        server.sendToTablet(s.getTableId(), RestaurantService.buildTabletSessionInfo(s));
    }

    /** Báo mọi quầy Lễ tân tải lại danh sách đặt bàn của ngày đó, cập nhật thẻ bàn liên quan */
    private void reservationsChanged(Reservation r) {
        server.broadcastToReception("RESERVATIONS_CHANGED|" + new SimpleDateFormat("yyyy-MM-dd").format(r.getReservedAt()));
        if (r.getTableId() != null) server.broadcastTableInfo(r.getTableId());
    }

    // ------------------------------------------------------------------ Tablet: đơn của bàn, thanh toán

    private void handleGetMyOrders() {
        // MY_ORDER|MÃ_ĐƠN|STT|GIỜ|TÓM_TẮT|TỔNG_TIỀN|TRẠNG_THÁI ... MY_ORDERS_END|TẠM_TÍNH
        TableSession s = SessionDao.getOpenSession(deviceId);
        if (s != null) {
            for (Order o : OrderDao.getOrdersBySession(s.getSessionId())) {
                sendMessage("MY_ORDER|" + o.getOrderId() + "|" + o.getOrderNo() + "|" + o.getShortTime() + "|" + enc(o.getItemsSummary())
                        + "|" + o.getTotalAmount() + "|" + enc(o.getOrderStatus()));
            }
        }
        sendMessage("MY_ORDERS_END|" + (s == null ? 0 : SessionDao.getSubtotal(s.getSessionId())));
    }

    private void handlePayRequest(NetworkPacket packet) {
        // PAY_REQUEST|HÌNH_THỨC -> Lễ tân nhận PAY_REQUEST|BÀN|HÌNH_THỨC|TẠM_TÍNH
        String method = packet.getText(1);
        if (!RestaurantService.PAY_CASH.equals(method) && !RestaurantService.PAY_TRANSFER.equals(method)) {
            sendMessage("PAY_REQUEST_FAIL|" + enc("Hình thức thanh toán không hợp lệ."));
            return;
        }
        TableSession s = SessionDao.getOpenSession(deviceId);
        long subtotal = (s == null) ? 0 : SessionDao.getSubtotal(s.getSessionId());
        if (s == null || subtotal <= 0) {
            sendMessage("PAY_REQUEST_FAIL|" + enc("Bàn chưa gọi món nào nên chưa cần thanh toán."));
            return;
        }
        SessionDao.setPayRequest(s.getSessionId(), method);
        server.log("[PAYMENT] " + deviceId + " yêu cầu thanh toán " + subtotal + " VNĐ bằng " + method);
        DatabaseManager.saveChatOrBellLog(deviceId, "PC_LeTan", "RING", "Yêu cầu thanh toán: " + method);
        server.broadcastToReception("PAY_REQUEST|" + deviceId + "|" + enc(method) + "|" + subtotal);
        server.sendToWaiter(s.getWaiterId(), "PAY_REQUEST|" + deviceId + "|" + enc(method) + "|" + subtotal, null);
        server.broadcastTableInfo(deviceId);
        sendMessage("PAY_REQUEST_OK|" + enc(method) + "|" + (server.getReceptionSessions().isEmpty() ? 0 : 1));
    }

    // ------------------------------------------------------------------ Bếp

    private void handleGetKitchenOrders(NetworkPacket packet) {
        // GET_KITCHEN_ORDERS|PENDING hoặc ALL -> KITCHEN_BEGIN|SỐ_ĐƠN, các dòng KITCHEN_ORDER, KITCHEN_END|CHẾ_ĐỘ
        boolean pendingOnly = !"ALL".equalsIgnoreCase(packet.getPart(1));
        List<Order> orders = OrderDao.getKitchenOrders(pendingOnly);
        sendMessage("KITCHEN_BEGIN|" + orders.size());
        for (Order o : orders) {
            sendMessage(RestaurantService.buildKitchenOrder(o));
        }
        sendMessage("KITCHEN_END|" + (pendingOnly ? "PENDING" : "ALL"));
    }
}
