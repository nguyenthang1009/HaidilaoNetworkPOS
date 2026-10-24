package com.haidilao.pos.server;

import com.haidilao.pos.database.DatabaseManager;
import com.haidilao.pos.database.SessionDao;
import com.haidilao.pos.database.TableDao;
import com.haidilao.pos.model.DiningTable;
import com.haidilao.pos.model.NetworkDevice;
import com.haidilao.pos.protocol.NetworkPacket;
import com.haidilao.pos.security.FirewallManager;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lõi POS Server: lắng nghe TCP cổng 8888, mỗi client một luồng (ClientSession),
 * giữ danh sách phiên đang kết nối theo vai trò và chuyển tiếp bản tin giữa Tablet, Lễ tân, Bếp.
 */
public class PosServer {
    private final int port;
    private ServerSocket serverSocket;
    private volatile boolean isRunning = false;
    private final FirewallManager firewall;
    private ServerEventListener listener;
    private DiscoveryServer discovery;

    private final Map<String, ClientSession> tabletSessions = new ConcurrentHashMap<>();
    private final Map<String, ClientSession> receptionSessions = new ConcurrentHashMap<>();
    private final Map<String, ClientSession> kitchenSessions = new ConcurrentHashMap<>();
    private final Map<String, ClientSession> waiterSessions = new ConcurrentHashMap<>();

    public PosServer(int port) {
        this.port = port;
        this.firewall = new FirewallManager();
    }

    public void setListener(ServerEventListener listener) {
        this.listener = listener;
    }

    public boolean isRunning() {
        return isRunning;
    }

    public void start() throws IOException {
        if (isRunning) return;
        serverSocket = new ServerSocket(port);
        isRunning = true;

        if (listener != null) {
            listener.onServerStarted(port);
        }

        log("[POS_SERVER] Đang lắng nghe kết nối TCP tại cổng " + port + "...");
        List<String> lanIps = getLanAddresses();
        if (lanIps.isEmpty()) {
            log("[POS_SERVER] Không tìm thấy card mạng LAN / Wi-Fi nào đang hoạt động (chỉ chạy được 127.0.0.1)");
        }
        for (String ip : lanIps) {
            log("[POS_SERVER] Địa chỉ máy chủ trong mạng: " + ip + " (các máy khác tự tìm thấy, không cần nhập)");
        }
        discovery = new DiscoveryServer(port, this);
        discovery.start();
        log("[SECURITY] Mã hóa đường truyền AES-128 CBC | Xác thực thiết bị bằng khóa + tài khoản nhân viên | Phân quyền lệnh theo vai trò (RBAC)");

        Thread acceptThread = new Thread(() -> {
            while (isRunning) {
                try {
                    Socket socket = serverSocket.accept();
                    ClientSession session = new ClientSession(socket, this, firewall);
                    new Thread(session, "ClientSession-" + socket.getInetAddress().getHostAddress()).start();
                } catch (IOException e) {
                    if (!isRunning) break;
                    log("[ERROR] Lỗi chấp nhận kết nối: " + e.getMessage());
                } catch (Throwable t) {
                    log("[ERROR] Lỗi ngoại lệ phiên kết nối: " + t.getMessage());
                }
            }
        }, "PosServer-Acceptor");
        acceptThread.start();
    }

    public void stop() {
        isRunning = false;
        if (discovery != null) discovery.stop();
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            tabletSessions.values().forEach(ClientSession::closeConnection);
            receptionSessions.values().forEach(ClientSession::closeConnection);
            kitchenSessions.values().forEach(ClientSession::closeConnection);
            waiterSessions.values().forEach(ClientSession::closeConnection);
            waiterSessions.clear();
            tabletSessions.clear();
            receptionSessions.clear();
            kitchenSessions.clear();
            if (listener != null) {
                listener.onServerStopped();
            }
            log("[POS_SERVER] Máy chủ đã dừng phục vụ.");
        } catch (IOException e) {
            log("[ERROR] Lỗi dừng Server: " + e.getMessage());
        }
    }

    public void log(String msg) {
        String timestamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
        String formatted = "[" + timestamp + "] " + msg;
        if (listener != null) {
            listener.onLog(formatted);
        } else {
            System.out.println(formatted);
        }
    }

    /**
     * Liệt kê các địa chỉ IPv4 mạng nội bộ của máy chủ kèm tên card mạng
     * (máy có nhiều card như Wi-Fi, Ethernet, VirtualBox... nên phải chọn đúng card đang nối với các máy khác)
     */
    private List<String> getLanAddresses() {
        List<String> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        result.add(addr.getHostAddress() + "   (card mạng: " + ni.getDisplayName() + ")");
                    }
                }
            }
        } catch (SocketException ignored) {}
        return result;
    }

    // ------------------------------------------------------------------ đăng ký / hủy phiên

    public void registerTablet(String deviceId, ClientSession session) {
        ClientSession old = tabletSessions.put(deviceId, session);
        if (old != null && old != session) {
            log("[CẢNH BÁO] Tên thiết bị [" + deviceId + "] đã có phiên khác đang dùng -> phiên mới thay thế. Kiểm tra xem 2 máy có chọn trùng tên bàn không!");
        }
        broadcastToReception(NetworkPacket.buildTableState(deviceId, "ONLINE"));
        broadcastTableInfo(deviceId);
        // Cho Tablet biết hiện có bao nhiêu quầy Lễ tân đang trực tuyến, và thông tin khách nếu bàn đã được mở
        session.sendMessage("RECEPTION_STATE|" + receptionSessions.size());
        session.sendMessage(RestaurantService.buildTabletSessionInfo(SessionDao.getOpenSession(deviceId)));
        notifyConnected(deviceId, session);
    }

    /**
     * Chỉ xóa nếu đúng là phiên đang được lưu trong map.
     * Trường hợp Tablet rớt Wi-Fi rồi kết nối lại: phiên cũ ngắt sau phiên mới, không được xóa nhầm phiên mới.
     */
    public void unregisterTablet(String deviceId, ClientSession session) {
        if (!tabletSessions.remove(deviceId, session)) return;
        broadcastToReception(NetworkPacket.buildTableState(deviceId, "OFFLINE"));
        broadcastTableInfo(deviceId);
        if (listener != null) {
            listener.onDeviceDisconnected(deviceId);
        }
    }

    public void registerReception(String deviceId, ClientSession session) {
        ClientSession old = receptionSessions.put(deviceId, session);
        if (old != null && old != session) {
            log("[CẢNH BÁO] Tên quầy Lễ tân [" + deviceId + "] đã có phiên khác đang dùng -> phiên mới thay thế.");
        }
        // Đồng bộ danh sách các bàn đang online sang quầy Lễ tân
        for (String tbl : tabletSessions.keySet()) {
            session.sendMessage(NetworkPacket.buildTableState(tbl, "ONLINE"));
        }
        broadcastToTablets("RECEPTION_STATE|" + receptionSessions.size());
        notifyConnected(deviceId, session);
    }

    public void unregisterReception(String deviceId, ClientSession session) {
        if (!receptionSessions.remove(deviceId, session)) return;
        broadcastToTablets("RECEPTION_STATE|" + receptionSessions.size());
        if (listener != null) {
            listener.onDeviceDisconnected(deviceId);
        }
    }

    public void registerKitchen(String deviceId, ClientSession session) {
        ClientSession old = kitchenSessions.put(deviceId, session);
        if (old != null && old != session) {
            log("[CẢNH BÁO] Tên màn hình Bếp [" + deviceId + "] đã có phiên khác đang dùng -> phiên mới thay thế.");
        }
        notifyConnected(deviceId, session);
    }

    public void unregisterKitchen(String deviceId, ClientSession session) {
        if (!kitchenSessions.remove(deviceId, session)) return;
        if (listener != null) {
            listener.onDeviceDisconnected(deviceId);
        }
    }

    public void registerWaiter(String deviceId, ClientSession session) {
        ClientSession old = waiterSessions.put(deviceId, session);
        if (old != null && old != session) {
            log("[CẢNH BÁO] Máy phục vụ [" + deviceId + "] đã có phiên khác đang dùng -> phiên mới thay thế.");
        }
        notifyConnected(deviceId, session);
        broadcastToReception("WAITERS_CHANGED");
    }

    public void unregisterWaiter(String deviceId, ClientSession session) {
        if (!waiterSessions.remove(deviceId, session)) return;
        broadcastToReception("WAITERS_CHANGED");
        if (listener != null) {
            listener.onDeviceDisconnected(deviceId);
        }
    }

    /** Gửi cho (các máy của) nhân viên phục vụ có mã staffId, trừ phiên except. Trả về true nếu có ít nhất 1 máy nhận. */
    public boolean sendToWaiter(int staffId, String msg, ClientSession except) {
        boolean sent = false;
        if (staffId <= 0) return false;
        for (ClientSession w : waiterSessions.values()) {
            if (w.getStaffId() == staffId && w != except) {
                w.sendMessage(msg);
                sent = true;
            }
        }
        return sent;
    }

    public boolean isWaiterOnline(int staffId) {
        for (ClientSession w : waiterSessions.values()) {
            if (w.getStaffId() == staffId) return true;
        }
        return false;
    }

    public void broadcastToWaiters(String msg) {
        for (ClientSession w : waiterSessions.values()) {
            w.sendMessage(msg);
        }
    }

    private void notifyConnected(String deviceId, ClientSession session) {
        if (listener != null) {
            String who = session.getStaffName().isEmpty() ? "Đang kết nối" : "Đang kết nối (" + session.getStaffName() + ")";
            listener.onDeviceConnected(deviceId, session.getRole().getDescription(), session.getRemoteIp(), session.getMacAddress(), who);
        }
    }

    public ClientSession getTabletSession(String deviceId) {
        if (deviceId == null) return null;
        ClientSession s = tabletSessions.get(deviceId);
        if (s != null) return s;

        // Tìm kiếm linh hoạt không phân biệt hoa thường hoặc tiền tố Tablet_
        String norm = deviceId.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        for (Map.Entry<String, ClientSession> entry : tabletSessions.entrySet()) {
            String keyNorm = entry.getKey().replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
            if (keyNorm.equals(norm) || keyNorm.endsWith(norm) || norm.endsWith(keyNorm)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public boolean isTabletOnline(String tableId) {
        return tabletSessions.containsKey(tableId);
    }

    // ------------------------------------------------------------------ gửi bản tin

    public void broadcastToReception(String msg) {
        for (ClientSession r : receptionSessions.values()) {
            r.sendMessage(msg);
        }
    }

    /**
     * Gửi cho mọi quầy Lễ tân trừ quầy except (dùng để đồng bộ câu trả lời giữa nhiều quầy)
     */
    public void broadcastToReceptionExcept(String msg, ClientSession except) {
        for (ClientSession r : receptionSessions.values()) {
            if (r != except) {
                r.sendMessage(msg);
            }
        }
    }

    public void broadcastToKitchen(String msg) {
        for (ClientSession k : kitchenSessions.values()) {
            k.sendMessage(msg);
        }
    }

    public void broadcastToTablets(String msg) {
        for (ClientSession t : tabletSessions.values()) {
            t.sendMessage(msg);
        }
    }

    /** Gửi Tablet của bàn (nếu đang kết nối) */
    public void sendToTablet(String tableId, String msg) {
        ClientSession t = tabletSessions.get(tableId);
        if (t != null) {
            t.sendMessage(msg);
        }
    }

    /** Báo cho mọi quầy Lễ tân trạng thái mới nhất của một bàn (TABLE_INFO) */
    public void broadcastTableInfo(String tableId) {
        // Bàn đổi trạng thái / đổi phục vụ: máy phục vụ tải lại danh sách bàn được giao
        broadcastToWaiters("MY_TABLES_CHANGED|" + tableId);
        if (receptionSessions.isEmpty()) return;
        DiningTable t = TableDao.getTable(tableId);
        if (t == null) return;
        broadcastToReception(RestaurantService.buildTableInfo(t, SessionDao.getOpenSession(tableId), isTabletOnline(tableId),
                RestaurantService.upcomingReservations().get(tableId)));
    }

    /** Quản lý vừa thêm / sửa / xóa bàn: các quầy Lễ tân tải lại sơ đồ bàn */
    public void broadcastTablesChanged() {
        broadcastToReception("TABLES_CHANGED");
    }

    public void notifyRevenueChanged() {
        if (listener != null) {
            listener.onRevenueUpdated(DatabaseManager.getTotalRevenue());
        }
    }

    // ------------------------------------------------------------------ khóa thiết bị

    /**
     * Quản trị khóa một thiết bị: đang kết nối thì ngắt ngay, lần sau đăng nhập bị từ chối cho tới khi được mở khóa.
     * Trả về false nếu thiết bị chưa từng kết nối (không có trong network_devices).
     */
    public boolean lockDevice(String deviceId) {
        if (DatabaseManager.getDeviceById(deviceId) == null) return false;
        DatabaseManager.updateDeviceSecurityStatus(deviceId, NetworkDevice.STATUS_LOCKED);
        DatabaseManager.saveSecurityLog(deviceId, null, "DEVICE_LOCK", "Quản trị viên khóa thiết bị");

        ClientSession session = tabletSessions.get(deviceId);
        if (session == null) session = receptionSessions.get(deviceId);
        if (session == null) session = kitchenSessions.get(deviceId);
        if (session == null) session = waiterSessions.get(deviceId);
        if (session != null) {
            session.sendMessage("KICKED|Thiết bị [" + deviceId + "] đã bị quản trị viên khóa. Liên hệ quản lý để mở khóa.");
            session.closeConnection();
        }
        log("[SECURITY] Quản trị viên đã khóa thiết bị: " + deviceId + (session != null ? " (đã ngắt kết nối)" : ""));
        return true;
    }

    public void unlockDevice(String deviceId) {
        DatabaseManager.updateDeviceSecurityStatus(deviceId, NetworkDevice.STATUS_ACTIVE);
        DatabaseManager.saveSecurityLog(deviceId, null, "DEVICE_UNLOCK", "Quản trị viên mở khóa thiết bị");
        log("[SECURITY] Quản trị viên đã mở khóa thiết bị: " + deviceId);
    }

    public void unlockAllDevices() {
        DatabaseManager.unlockAllDevices();
        DatabaseManager.saveSecurityLog("ALL", null, "DEVICE_UNLOCK", "Quản trị viên mở khóa toàn bộ thiết bị");
        log("[SECURITY] Quản trị viên đã mở khóa toàn bộ thiết bị.");
    }

    public Map<String, ClientSession> getTabletSessions() {
        return Collections.unmodifiableMap(tabletSessions);
    }

    public Map<String, ClientSession> getReceptionSessions() {
        return Collections.unmodifiableMap(receptionSessions);
    }

    public Map<String, ClientSession> getWaiterSessions() {
        return Collections.unmodifiableMap(waiterSessions);
    }

    public Map<String, ClientSession> getKitchenSessions() {
        return Collections.unmodifiableMap(kitchenSessions);
    }
}
