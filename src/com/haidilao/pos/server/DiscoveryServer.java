package com.haidilao.pos.server;

import com.haidilao.pos.database.SettingsDao;
import com.haidilao.pos.database.TableDao;
import com.haidilao.pos.model.DiningTable;
import com.haidilao.pos.protocol.NetworkPacket;
import com.haidilao.pos.protocol.ProtocolConstants;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

/**
 * Dịch vụ tìm máy chủ bằng UDP: lắng nghe cổng 8889, ai gửi "LTO_DISCOVER|1" (thường là broadcast)
 * thì trả lời thẳng về địa chỉ người hỏi:
 *   LTO_SERVER|1|CỔNG_TCP|TÊN_NHÀ_HÀNG|MÃ_BÀN=TÊN_BÀN;MÃ_BÀN=TÊN_BÀN...
 * Nhờ vậy máy Lễ tân / Bếp / Tablet trong cùng mạng Wi-Fi không cần nhập IP máy chủ.
 */
public class DiscoveryServer {
    private final int tcpPort;
    private final PosServer server;
    private DatagramSocket socket;
    private volatile boolean running;

    public DiscoveryServer(int tcpPort, PosServer server) {
        this.tcpPort = tcpPort;
        this.server = server;
    }

    public void start() {
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(ProtocolConstants.DISCOVERY_PORT));
        } catch (SocketException e) {
            server.log("[DISCOVERY] Không mở được UDP " + ProtocolConstants.DISCOVERY_PORT + " (" + e.getMessage()
                    + "). Các máy khác sẽ không tự tìm thấy máy chủ.");
            return;
        }
        running = true;
        Thread t = new Thread(this::loop, "DiscoveryServer");
        t.setDaemon(true);
        t.start();
        server.log("[DISCOVERY] Đang trả lời yêu cầu tìm máy chủ trên UDP " + ProtocolConstants.DISCOVERY_PORT + " (broadcast trong mạng LAN / Wi-Fi)");
    }

    private void loop() {
        byte[] buf = new byte[512];
        while (running) {
            try {
                DatagramPacket req = new DatagramPacket(buf, buf.length);
                socket.receive(req);
                String text = new String(req.getData(), 0, req.getLength(), StandardCharsets.UTF_8).trim();
                if (!ProtocolConstants.DISCOVERY_REQUEST.equals(text)) continue; // gói lạ: bỏ qua
                byte[] reply = buildReply().getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(reply, reply.length, req.getAddress(), req.getPort()));
            } catch (IOException e) {
                if (!running) break;
            } catch (RuntimeException e) {
                server.log("[DISCOVERY] Lỗi trả lời yêu cầu tìm máy chủ: " + e.getMessage());
            }
        }
    }

    private String buildReply() {
        StringBuilder tables = new StringBuilder();
        for (DiningTable t : TableDao.getAllTables()) {
            if (tables.length() > 0) tables.append(';');
            tables.append(NetworkPacket.enc(t.getTableId())).append('=').append(NetworkPacket.enc(t.getTableName()));
        }
        String name = SettingsDao.get(SettingsDao.RESTAURANT_NAME, "Nhà hàng");
        return ProtocolConstants.DISCOVERY_REPLY + "|" + tcpPort + "|" + NetworkPacket.enc(name) + "|" + tables;
    }

    public void stop() {
        running = false;
        if (socket != null) socket.close();
    }
}
