package com.haidilao.pos.server;

import com.haidilao.pos.database.DatabaseManager;
import com.haidilao.pos.model.NetworkDevice;
import com.haidilao.pos.protocol.NetworkPacket;
import com.haidilao.pos.protocol.ProtocolConstants;
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
 * Lõi POS Server lắng nghe TCP cổng 8888, tích hợp Firewall ACL và điều phối mạng
 */
public class PosServer {
    private final int port;
    private ServerSocket serverSocket;
    private volatile boolean isRunning = false;
    private final FirewallManager firewall;
    private ServerEventListener listener;

    private final Map<String, ClientSession> tabletSessions = new ConcurrentHashMap<>();
    private final Map<String, ClientSession> receptionSessions = new ConcurrentHashMap<>();

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
            log("[POS_SERVER] => Trên các máy Lễ tân / Tablet khác, nhập IP: " + ip);
        }
        log("[ACL-ACTIVE] Rule 10: permit tcp 192.168.20.0/24 host 192.168.10.50 eq 8888 (Tablet Wi-Fi)");
        log("[ACL-ACTIVE] Rule 20: permit tcp 192.168.10.0/24 host 192.168.10.50 eq 8888 (Quầy Lễ Tân)");
        log("[ACL-ACTIVE] Rule 30: deny ip 192.168.20.0/24 192.168.10.0/24 (Chặn P2P Tablet sang Lễ tân)");
        log("[CRYPTO-ACTIVE] Đã kích hoạt mã hóa dữ liệu đường truyền: AES-128 CBC (Data-in-Transit Encryption)");

        Thread acceptThread = new Thread(() -> {
            while (isRunning) {
                try {
                    Socket socket = serverSocket.accept();
                    ClientSession session = new ClientSession(socket, this, firewall);
                    new Thread(session).start();
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
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            tabletSessions.values().forEach(ClientSession::closeConnection);
            receptionSessions.values().forEach(ClientSession::closeConnection);
            tabletSessions.clear();
            receptionSessions.clear();
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

    public void registerTablet(String deviceId, ClientSession session) {
        ClientSession old = tabletSessions.put(deviceId, session);
        if (old != null && old != session) {
            log("[CẢNH BÁO] Tên thiết bị [" + deviceId + "] đã có phiên khác đang dùng -> phiên mới thay thế. Kiểm tra xem 2 máy có chọn trùng tên bàn không!");
        }
        broadcastToReception(NetworkPacket.buildTableState(deviceId, "ONLINE"));
        // Cho Tablet biết hiện có bao nhiêu quầy Lễ tân đang trực tuyến
        session.sendMessage("RECEPTION_STATE|" + receptionSessions.size());
        if (listener != null) {
            listener.onDeviceConnected(deviceId, session.getVlanName(), session.getSimulatedIp(), session.getMacAddress(), "ACL Permitted (8888)");
        }
    }

    /**
     * Chỉ xóa nếu đúng là phiên đang được lưu trong map.
     * Trường hợp Tablet rớt Wi-Fi rồi kết nối lại: phiên cũ ngắt sau phiên mới, không được xóa nhầm phiên mới.
     */
    public void unregisterTablet(String deviceId, ClientSession session) {
        if (!tabletSessions.remove(deviceId, session)) return;
        broadcastToReception(NetworkPacket.buildTableState(deviceId, "OFFLINE"));
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
        if (listener != null) {
            listener.onDeviceConnected(deviceId, session.getVlanName(), session.getSimulatedIp(), session.getMacAddress(), "SecureSticky (Up)");
        }
    }

    public void unregisterReception(String deviceId, ClientSession session) {
        if (!receptionSessions.remove(deviceId, session)) return;
        broadcastToTablets("RECEPTION_STATE|" + receptionSessions.size());
        if (listener != null) {
            listener.onDeviceDisconnected(deviceId);
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

    public void broadcastToTablets(String msg) {
        for (ClientSession t : tabletSessions.values()) {
            t.sendMessage(msg);
        }
    }

    public void notifyRevenueChanged(long totalRevenue) {
        if (listener != null) {
            listener.onRevenueUpdated(totalRevenue);
        }
    }

    /**
     * Khóa cổng thiết bị vi phạm (Err-Disable / Shutdown) từ Admin
     */
    public boolean shutdownDevice(String deviceId) {
        ClientSession session = tabletSessions.get(deviceId);
        if (session == null) {
            session = receptionSessions.get(deviceId);
        }

        if (session != null) {
            session.sendMessage("KICKED|Cổng Switch của thiết bị [" + deviceId + "] đã bị đưa vào trạng thái Err-Disable bởi PC_Admin!");
            session.closeConnection();
            log("[PORT-SECURITY] PC_Admin đã kích hoạt Violation Shutdown đối với: " + deviceId);
            DatabaseManager.saveChatOrBellLog("PC_Admin", deviceId, "SECURITY", "Violation Shutdown (Err-Disable)");
            DatabaseManager.updateDeviceSecurityStatus(deviceId, "ERR_DISABLE");
            return true;
        }
        return false;
    }

    /**
     * Mở khóa cổng thiết bị (No Shutdown) từ Admin
     */
    public boolean unlockDevice(String deviceId) {
        DatabaseManager.updateDeviceSecurityStatus(deviceId, "STICKY_ACTIVE");
        log("[PORT-SECURITY] PC_Admin đã mở khóa cổng Switch (no shutdown) cho: " + deviceId);
        DatabaseManager.saveChatOrBellLog("PC_Admin", deviceId, "SECURITY", "Port Restored (No Shutdown)");
        return true;
    }

    /**
     * Phục hồi toàn bộ cổng mạng đang bị Err-Disable
     */
    public void restoreAllPorts() {
        DatabaseManager.restoreAllDevicePorts();
        log("[PORT-SECURITY] PC_Admin đã mở khóa (no shutdown) toàn bộ cổng Switch trên hệ thống!");
    }

    public Map<String, ClientSession> getTabletSessions() {
        return Collections.unmodifiableMap(tabletSessions);
    }

    public Map<String, ClientSession> getReceptionSessions() {
        return Collections.unmodifiableMap(receptionSessions);
    }
}
