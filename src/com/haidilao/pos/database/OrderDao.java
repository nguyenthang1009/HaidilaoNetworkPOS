package com.haidilao.pos.database;

import com.haidilao.pos.model.Order;
import com.haidilao.pos.model.OrderLine;
import com.haidilao.pos.model.TableSession;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Truy vấn bảng orders và order_details
 */
public final class OrderDao {
    private static final String SELECT = "SELECT o.order_id, o.order_no, o.session_id, o.table_id, t.table_name,"
            + " o.items_summary, o.total_amount, o.order_status, o.note, o.created_at"
            + " FROM orders o LEFT JOIN dining_tables t ON t.table_id = o.table_id";

    private OrderDao() {}

    /**
     * Lưu đơn mới cùng các dòng món trong MỘT transaction:
     * ghi vào lượt khách đang mở của bàn, cấp số thứ tự trong ngày, ghi orders + order_details.
     * synchronized: chỉ một đơn được cấp số thứ tự tại một thời điểm, không bao giờ trùng STT.
     */
    public static synchronized Order insertOrder(String tableId, String note, List<OrderLine> lines,
                                                 String itemsSummary, long total) {
        String insertOrder = "INSERT INTO orders (order_no, session_id, table_id, items_summary, total_amount, order_status, note)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)";
        String insertLine = "INSERT INTO order_details (order_id, item_id, combo_id, item_name, quantity, unit_price, note)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int sessionId = SessionDao.requireOpenSession(conn, tableId);
                int orderNo;
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(order_no), 0) + 1 FROM orders WHERE created_at >= CURDATE()")) {
                    rs.next();
                    orderNo = rs.getInt(1);
                }
                int orderId;
                try (PreparedStatement ps = conn.prepareStatement(insertOrder, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setInt(1, orderNo);
                    ps.setInt(2, sessionId);
                    ps.setString(3, tableId);
                    ps.setString(4, itemsSummary);
                    ps.setLong(5, total);
                    ps.setString(6, Order.STATUS_WAITING);
                    ps.setString(7, note);
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        rs.next();
                        orderId = rs.getInt(1);
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(insertLine)) {
                    for (OrderLine l : lines) {
                        ps.setInt(1, orderId);
                        if (l.isCombo()) {
                            ps.setNull(2, Types.INTEGER);
                            ps.setInt(3, l.getRefId());
                        } else {
                            ps.setInt(2, l.getRefId());
                            ps.setNull(3, Types.INTEGER);
                        }
                        ps.setString(4, l.getName());
                        ps.setInt(5, l.getQuantity());
                        ps.setLong(6, l.getUnitPrice());
                        ps.setString(7, l.getNote().isEmpty() ? null : l.getNote());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
                Order o = getOrder(conn, orderId);
                if (o != null) o.getLines().addAll(lines);
                return o;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            System.err.println("[OrderDao] Lỗi lưu đơn hàng: " + e.getMessage());
            return null;
        }
    }

    public static Order getOrder(int orderId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            Order o = getOrder(conn, orderId);
            if (o != null) o.getLines().addAll(getLines(conn, orderId));
            return o;
        } catch (SQLException e) {
            System.err.println("[OrderDao] Lỗi đọc đơn: " + e.getMessage());
            return null;
        }
    }

    private static Order getOrder(Connection conn, int orderId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT + " WHERE o.order_id = ?")) {
            ps.setInt(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        }
    }

    /**
     * Đơn cho quầy Lễ tân: đơn hôm nay + đơn của các bàn đang phục vụ (khách ngồi từ hôm trước).
     */
    public static List<Order> getReceptionOrders() {
        String sql = SELECT + " WHERE o.created_at >= CURDATE()"
                + " OR o.session_id IN (SELECT session_id FROM table_sessions WHERE status = ?) ORDER BY o.order_id";
        return query(sql, false, TableSession.STATUS_OPEN);
    }

    /**
     * Đơn cho bếp. pendingOnly = true: đơn chưa nấu xong (CHỜ BẾP, ĐANG NẤU); false: mọi đơn hôm nay.
     * Kèm danh sách món để bếp xem.
     */
    public static List<Order> getKitchenOrders(boolean pendingOnly) {
        if (pendingOnly) {
            return query(SELECT + " WHERE o.order_status IN (?, ?) ORDER BY o.order_id", true,
                    Order.STATUS_WAITING, Order.STATUS_COOKING);
        }
        return query(SELECT + " WHERE o.created_at >= CURDATE() ORDER BY o.order_id", true);
    }

    public static List<Order> getOrdersBySession(int sessionId) {
        List<Order> list = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT + " WHERE o.session_id = ? ORDER BY o.order_id")) {
            ps.setInt(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(read(rs));
            }
            for (Order o : list) {
                o.getLines().addAll(getLines(conn, o.getOrderId()));
            }
        } catch (SQLException e) {
            System.err.println("[OrderDao] Lỗi đọc đơn của phiên: " + e.getMessage());
        }
        return list;
    }

    private static List<Order> query(String sql, boolean withLines, String... params) {
        List<Order> list = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(read(rs));
            }
            if (withLines) {
                for (Order o : list) {
                    o.getLines().addAll(getLines(conn, o.getOrderId()));
                }
            }
        } catch (SQLException e) {
            System.err.println("[OrderDao] Lỗi đọc danh sách đơn: " + e.getMessage());
        }
        return list;
    }

    private static List<OrderLine> getLines(Connection conn, int orderId) throws SQLException {
        List<OrderLine> lines = new ArrayList<>();
        String sql = "SELECT item_id, combo_id, item_name, quantity, unit_price, note FROM order_details WHERE order_id = ? ORDER BY detail_id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int comboId = rs.getInt("combo_id");
                    boolean isCombo = !rs.wasNull();
                    int itemId = rs.getInt("item_id");
                    lines.add(new OrderLine(
                            isCombo ? OrderLine.TYPE_COMBO : OrderLine.TYPE_ITEM,
                            isCombo ? comboId : itemId,
                            rs.getString("item_name"),
                            rs.getInt("quantity"),
                            rs.getLong("unit_price"),
                            rs.getString("note")
                    ));
                }
            }
        }
        return lines;
    }

    private static Order read(ResultSet rs) throws SQLException {
        Order o = new Order(
                rs.getInt("order_id"),
                rs.getString("table_id"),
                rs.getString("items_summary"),
                rs.getLong("total_amount"),
                rs.getString("order_status"),
                rs.getTimestamp("created_at")
        );
        o.setOrderNo(rs.getInt("order_no"));
        o.setSessionId(rs.getInt("session_id"));
        o.setTableName(rs.getString("table_name"));
        o.setNote(rs.getString("note"));
        return o;
    }

    public static boolean updateStatus(int orderId, String newStatus) {
        String sql = "UPDATE orders SET order_status = ? WHERE order_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newStatus);
            ps.setInt(2, orderId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[OrderDao] Lỗi cập nhật trạng thái đơn: " + e.getMessage());
            return false;
        }
    }
}
