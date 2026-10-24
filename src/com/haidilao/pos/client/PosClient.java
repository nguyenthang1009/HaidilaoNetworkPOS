package com.haidilao.pos.client;

import com.haidilao.pos.protocol.CommandType;
import com.haidilao.pos.protocol.NetworkPacket;
import com.haidilao.pos.protocol.ProtocolConstants;
import com.haidilao.pos.security.CryptoManager;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * Điều phối kết nối Socket Client (Tablet, Quầy Lễ tân, Màn hình Bếp)
 * Tích hợp tự động kết nối lại (Auto-Reconnect) và bắt tay bảo mật
 */
public class PosClient {
    private static final int CONNECT_TIMEOUT_MS = 3000;

    private volatile String host;   // null = tự tìm máy chủ trong mạng (UDP broadcast) trước mỗi lần kết nối
    private volatile int port;
    private final boolean autoDiscover;
    private final String role;
    private final String deviceId;
    private final String authKey;
    private final String username;   // tài khoản nhân viên (Lễ tân, Bếp); Tablet để null
    private final String password;
    private ClientEventListener listener;

    // Thông tin nhân viên Server trả về trong AUTH_ACK
    private volatile int staffId;
    private volatile String staffName = "";
    private volatile String staffRole = "";

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private volatile boolean isConnected = false;
    private volatile boolean isRunning = false;
    private volatile boolean isKicked = false;

    public PosClient(String host, int port, String role, String deviceId, String authKey) {
        this(host, port, role, deviceId, authKey, null, null);
    }

    /** Máy nhân viên: đăng nhập kèm tài khoản + mật khẩu */
    public PosClient(String host, int port, String role, String deviceId, String authKey, String username, String password) {
        this.autoDiscover = (host == null || host.trim().isEmpty() || "auto".equalsIgnoreCase(host.trim()));
        this.host = autoDiscover ? null : host.trim();
        this.port = port;
        this.role = role;
        this.deviceId = deviceId;
        this.authKey = authKey;
        this.username = username;
        this.password = password;
    }

    public int getStaffId() {
        return staffId;
    }

    public String getStaffName() {
        return staffName;
    }

    public String getStaffRole() {
        return staffRole;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setListener(ClientEventListener listener) {
        this.listener = listener;
    }

    public boolean isConnected() {
        return isConnected;
    }

    public void connect() {
        if (isRunning) return;
        isRunning = true;
        isKicked = false;

        new Thread(() -> {
            boolean failReported = false;
            while (isRunning && !isKicked) {
                boolean connectedThisRound = false;
                String reason = "Server đã đóng kết nối";
                try {
                    if (autoDiscover) {
                        // Tìm máy chủ trong mạng Wi-Fi mỗi lần kết nối, nên máy chủ đổi IP (DHCP) vẫn tìm lại được
                        ServerLocator.Found found = ServerLocator.discover(ProtocolConstants.DISCOVERY_TIMEOUT_MS);
                        if (found == null) {
                            throw new IOException("Chưa tìm thấy máy chủ trong mạng Wi-Fi (máy chủ đã bật chưa?)");
                        }
                        host = found.host;
                        port = found.port;
                    }
                    // Đặt thời gian chờ 3 giây: máy chủ không phản hồi thì báo lỗi nhanh, không bị treo lâu
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                    out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
                    in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));

                    // Lấy địa chỉ MAC thật của máy
                    String realMac = getLocalMacAddress();

                    // Gửi gói tin xác thực bắt tay AUTH kèm MAC thật (Mã hóa AES-128 trước khi đưa lên mạng)
                    String rawAuth = (username == null)
                            ? NetworkPacket.buildAuth(role, deviceId, authKey, realMac)
                            : NetworkPacket.buildAuth(role, deviceId, authKey, realMac, username, password);
                    out.println(CryptoManager.encrypt(rawAuth));

                    // Quá 15 giây không nhận được gì (kể cả PONG) thì readLine() ném SocketTimeoutException -> coi như mất kết nối
                    socket.setSoTimeout(ProtocolConstants.HEARTBEAT_TIMEOUT_MS);

                    // Chờ Server trả lời xác thực: chỉ khi nhận AUTH_ACK mới tính là đã kết nối
                    String authReply = in.readLine();
                    if (authReply == null) {
                        throw new IOException("Server đóng kết nối khi đang xác thực");
                    }
                    authReply = CryptoManager.decrypt(authReply);
                    if (authReply.startsWith(CommandType.AUTH_FAIL + "|")) {
                        // Bị từ chối (sai khóa, thiết bị đang bị khóa cổng...) -> báo lý do và NGỪNG tự kết nối lại
                        isRunning = false;
                        if (listener != null) {
                            listener.onAuthFailed(authReply.substring((CommandType.AUTH_FAIL + "|").length()));
                        }
                        return;
                    }
                    if (!authReply.startsWith(CommandType.AUTH_ACK.name())) {
                        throw new IOException("Phản hồi xác thực không hợp lệ");
                    }
                    // AUTH_ACK|LỜI_CHÀO|MÃ_NV|TÊN_NV|VAI_TRÒ_NV (Tablet chỉ có lời chào)
                    String[] ack = NetworkPacket.split(authReply);
                    if (ack.length >= 5) {
                        staffId = NetworkPacket.parseInt(ack[2], 0);
                        staffName = NetworkPacket.dec(ack[3]);
                        staffRole = NetworkPacket.dec(ack[4]);
                    }

                    isConnected = true;
                    connectedThisRound = true;
                    failReported = false;
                    startHeartbeat(out);
                    if (listener != null) {
                        listener.onConnected();
                        listener.onMessageReceived(authReply); // để giao diện hiện lời chào của Server
                    }

                    String line;
                    while (isConnected && (line = in.readLine()) != null) {
                        // Tự động giải mã gói tin an toàn nhận từ mạng (AES-128)
                        line = CryptoManager.decrypt(line);

                        if (CommandType.PONG.name().equals(line)) {
                            continue; // gói heartbeat, chỉ để biết Server còn sống
                        }

                        if (line.startsWith("KICKED|")) {
                            String kickReason = line.substring("KICKED|".length());
                            isKicked = true;
                            isConnected = false;
                            isRunning = false;
                            if (listener != null) {
                                listener.onKicked(kickReason);
                            }
                            break;
                        }

                        if (listener != null) {
                            listener.onMessageReceived(line);
                        }
                    }
                } catch (SocketTimeoutException e) {
                    reason = connectedThisRound
                            ? "Quá " + ProtocolConstants.HEARTBEAT_TIMEOUT_MS / 1000 + " giây không nhận được tín hiệu từ Server (mất mạng?)"
                            : e.getMessage();
                } catch (IOException e) {
                    reason = e.getMessage();
                } finally {
                    cleanSocket();
                }

                // Báo cho giao diện: mất kết nối (đã từng vào được) hoặc không kết nối được (chỉ báo 1 lần, tránh spam)
                if (listener != null && !isKicked) {
                    if (connectedThisRound) {
                        listener.onDisconnected(reason);
                    } else if (!failReported) {
                        failReported = true;
                        listener.onDisconnected(host == null ? reason : "Không kết nối được tới máy chủ " + host + ":" + port + " (" + reason + ")");
                    }
                }

                // Nếu chưa kết nối và chưa bị admin khóa cổng, tự động thử lại sau 2.5 giây
                if (isRunning && !isKicked) {
                    try {
                        Thread.sleep(2500);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }, "PosClient-Worker-" + deviceId).start();
    }

    /**
     * Heartbeat: thread phụ gửi PING mỗi 5 giây để Server biết máy này còn sống.
     * Gắn với đúng PrintWriter của lần kết nối này: kết nối lại thì thread cũ tự dừng.
     */
    private void startHeartbeat(PrintWriter writer) {
        Thread heartbeat = new Thread(() -> {
            while (isConnected && out == writer) {
                try {
                    Thread.sleep(ProtocolConstants.HEARTBEAT_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                if (isConnected && out == writer) {
                    writer.println(CryptoManager.encrypt(CommandType.PING.name()));
                }
            }
        }, "PosClient-Heartbeat-" + deviceId);
        heartbeat.setDaemon(true);
        heartbeat.start();
    }

    public void send(String msg) {
        if (out != null && isConnected) {
            // Luôn mã hóa gói tin bằng AES-128 trước khi đưa vào Socket gửi đi
            out.println(CryptoManager.encrypt(msg));
        }
    }

    private void cleanSocket() {
        isConnected = false;
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {}
    }

    public void disconnect() {
        isRunning = false;
        cleanSocket();
    }

    public String getHost() {
        return host;
    }

    /**
     * Tự động dò tìm địa chỉ MAC vật lý thật của máy tính (Wi-Fi hoặc Ethernet)
     */
    public static String getLocalMacAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                byte[] mac = ni.getHardwareAddress();
                if (mac != null && mac.length == 6) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < mac.length; i++) {
                        sb.append(String.format("%02X", mac[i]));
                        if (i % 2 == 1 && i < mac.length - 1) sb.append(".");
                    }
                    return sb.toString();
                }
            }
        } catch (Exception ignored) {}
        return "0040.0BD3.2C99";
    }
}
