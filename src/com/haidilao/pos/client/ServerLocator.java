package com.haidilao.pos.client;

import com.haidilao.pos.protocol.NetworkPacket;
import com.haidilao.pos.protocol.ProtocolConstants;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Tìm máy chủ trong mạng LAN / Wi-Fi bằng UDP broadcast (không cần nhập IP).
 * Gửi "LTO_DISCOVER|1" tới địa chỉ broadcast của mọi card mạng (và 127.0.0.1 cho trường hợp chạy cùng máy),
 * máy chủ trả lời "LTO_SERVER|1|CỔNG|TÊN|DANH_SÁCH_BÀN"; IP nguồn của gói trả lời chính là IP máy chủ.
 */
public final class ServerLocator {
    private ServerLocator() {}

    /** Kết quả tìm được */
    public static final class Found {
        public final String host;
        public final int port;
        public final String restaurantName;
        public final List<String[]> tables; // {mã bàn, tên bàn}

        Found(String host, int port, String restaurantName, List<String[]> tables) {
            this.host = host;
            this.port = port;
            this.restaurantName = restaurantName;
            this.tables = tables;
        }
    }

    /**
     * Hỏi trong mạng, chờ tối đa timeoutMs. Trả về máy chủ trả lời đầu tiên, null nếu không có.
     * Có thể chỉ định IP cố định bằng tham số khi chạy: -Dpos.server=192.168.1.10
     */
    public static Found discover(int timeoutMs) {
        String fixed = System.getProperty("pos.server");
        if (fixed != null && !fixed.trim().isEmpty()) {
            return new Found(fixed.trim(), ProtocolConstants.SERVER_PORT, "", new ArrayList<>());
        }
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            byte[] req = ProtocolConstants.DISCOVERY_REQUEST.getBytes(StandardCharsets.UTF_8);
            for (InetAddress target : targets()) {
                try {
                    socket.send(new DatagramPacket(req, req.length, target, ProtocolConstants.DISCOVERY_PORT));
                } catch (IOException ignored) {
                    // card mạng không gửi được broadcast (VPN, card ảo...) thì thử card khác
                }
            }
            long end = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[8192];
            while (true) {
                int left = (int) (end - System.currentTimeMillis());
                if (left <= 0) return null;
                socket.setSoTimeout(left);
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(resp);
                } catch (SocketTimeoutException e) {
                    return null;
                }
                Found f = parse(new String(resp.getData(), 0, resp.getLength(), StandardCharsets.UTF_8), resp.getAddress());
                if (f != null) return f;
            }
        } catch (IOException e) {
            return null;
        }
    }

    /** Địa chỉ gửi gói hỏi: 127.0.0.1, 255.255.255.255 và broadcast riêng của từng card mạng đang bật */
    private static Set<InetAddress> targets() {
        Set<InetAddress> set = new LinkedHashSet<>();
        try {
            set.add(InetAddress.getByName("127.0.0.1"));
            set.add(InetAddress.getByName("255.255.255.255"));
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getBroadcast() != null) set.add(ia.getBroadcast());
                }
            }
        } catch (IOException ignored) {}
        return set;
    }

    private static Found parse(String text, InetAddress from) {
        String[] f = NetworkPacket.split(text.trim());
        String prefix = ProtocolConstants.DISCOVERY_REPLY + "|";
        if (!text.startsWith(prefix) || f.length < 4) return null;
        int port = NetworkPacket.parseInt(f[2], -1);
        if (port <= 0) return null;
        List<String[]> tables = new ArrayList<>();
        if (f.length > 4 && !f[4].isEmpty()) {
            for (String pair : f[4].split(";")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) tables.add(new String[]{NetworkPacket.dec(kv[0]), NetworkPacket.dec(kv[1])});
            }
        }
        return new Found(from.getHostAddress(), port, NetworkPacket.dec(f[3]), tables);
    }
}
