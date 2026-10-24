package com.haidilao.pos.database;

import com.haidilao.pos.model.Order;
import com.haidilao.pos.model.StatRow;
import com.haidilao.pos.model.TableSession;

import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Các truy vấn thống kê trong khoảng ngày [from, to] (tính cả 2 đầu).
 * Doanh thu = tiền khách đã thanh toán (phiên đã đóng). Số đơn không tính đơn đã hủy.
 */
public final class StatisticsDao {
    private StatisticsDao() {}

    /** Số liệu tổng quan */
    public static class Summary {
        public long revenue;
        public long orderCount;
        public long guestCount;
        public long sessionCount;

        /** Trung bình mỗi lượt khách thanh toán */
        public long getAveragePerSession() {
            return sessionCount == 0 ? 0 : revenue / sessionCount;
        }
    }

    public static Summary getSummary(Date from, Date to) {
        Summary s = new Summary();
        // Doanh thu gồm cả tiền cọc giữ lại của lượt bị hủy; số lượt và số khách chỉ tính lượt đã thanh toán
        String paid = "SELECT COALESCE(SUM(total_paid), 0), COALESCE(SUM(status = ?), 0),"
                + " COALESCE(SUM(CASE WHEN status = ? THEN guest_count ELSE 0 END), 0) FROM table_sessions"
                + " WHERE status <> ? AND DATE(closed_at) BETWEEN ? AND ?";
        String orders = "SELECT COUNT(*) FROM orders WHERE order_status <> ? AND DATE(created_at) BETWEEN ? AND ?";
        try (Connection conn = DatabaseManager.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(paid)) {
                ps.setString(1, TableSession.STATUS_PAID);
                ps.setString(2, TableSession.STATUS_PAID);
                ps.setString(3, TableSession.STATUS_OPEN);
                ps.setDate(4, from);
                ps.setDate(5, to);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        s.revenue = rs.getLong(1);
                        s.sessionCount = rs.getLong(2);
                        s.guestCount = rs.getLong(3);
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(orders)) {
                ps.setString(1, Order.STATUS_CANCELLED);
                ps.setDate(2, from);
                ps.setDate(3, to);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) s.orderCount = rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            System.err.println("[StatisticsDao] Lỗi thống kê tổng quan: " + e.getMessage());
        }
        return s;
    }

    /** Theo ngày: nhãn dd/MM/yyyy, count = số đơn, amount = doanh thu. Sắp xếp ngày tăng dần. */
    public static List<StatRow> getDaily(Date from, Date to) {
        Map<Date, StatRow> map = new TreeMap<>();
        SimpleDateFormat fmt = new SimpleDateFormat("dd/MM/yyyy");
        String orders = "SELECT DATE(created_at) AS d, COUNT(*) FROM orders WHERE order_status <> ?"
                + " AND DATE(created_at) BETWEEN ? AND ? GROUP BY DATE(created_at)";
        String revenue = "SELECT DATE(closed_at) AS d, SUM(total_paid) FROM table_sessions WHERE status <> ?"
                + " AND DATE(closed_at) BETWEEN ? AND ? GROUP BY DATE(closed_at)";
        try (Connection conn = DatabaseManager.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(orders)) {
                ps.setString(1, Order.STATUS_CANCELLED);
                ps.setDate(2, from);
                ps.setDate(3, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Date d = rs.getDate(1);
                        map.computeIfAbsent(d, k -> new StatRow(fmt.format(k), 0, 0)).setCount(rs.getLong(2));
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(revenue)) {
                ps.setString(1, TableSession.STATUS_OPEN);
                ps.setDate(2, from);
                ps.setDate(3, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Date d = rs.getDate(1);
                        map.computeIfAbsent(d, k -> new StatRow(fmt.format(k), 0, 0)).setAmount(rs.getLong(2));
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[StatisticsDao] Lỗi thống kê theo ngày: " + e.getMessage());
        }
        return new ArrayList<>(map.values());
    }

    /** Món / combo bán chạy: count = số lượng, amount = tiền bán. */
    public static List<StatRow> getTopItems(Date from, Date to, int limit) {
        String sql = "SELECT d.item_name, SUM(d.quantity) AS qty, SUM(d.quantity * d.unit_price) AS amount"
                + " FROM order_details d JOIN orders o ON o.order_id = d.order_id"
                + " WHERE o.order_status <> ? AND DATE(o.created_at) BETWEEN ? AND ?"
                + " GROUP BY d.item_name ORDER BY qty DESC, amount DESC LIMIT ?";
        List<StatRow> list = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, Order.STATUS_CANCELLED);
            ps.setDate(2, from);
            ps.setDate(3, to);
            ps.setInt(4, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new StatRow(rs.getString(1), rs.getLong(2), rs.getLong(3)));
                }
            }
        } catch (SQLException e) {
            System.err.println("[StatisticsDao] Lỗi thống kê món bán chạy: " + e.getMessage());
        }
        return list;
    }

    /** Theo bàn: count = số đơn, amount = doanh thu đã thanh toán. Đủ mọi bàn, kể cả bàn chưa có khách. */
    public static List<StatRow> getByTable(Date from, Date to) {
        Map<String, StatRow> map = new LinkedHashMap<>();
        String tables = "SELECT table_id, table_name FROM dining_tables ORDER BY sort_order, table_id";
        String orders = "SELECT table_id, COUNT(*) FROM orders WHERE order_status <> ? AND DATE(created_at) BETWEEN ? AND ? GROUP BY table_id";
        String revenue = "SELECT table_id, SUM(total_paid) FROM table_sessions WHERE status <> ? AND DATE(closed_at) BETWEEN ? AND ? GROUP BY table_id";
        Map<String, String> names = new LinkedHashMap<>();
        try (Connection conn = DatabaseManager.getConnection()) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(tables)) {
                while (rs.next()) {
                    names.put(rs.getString(1), rs.getString(2));
                    map.put(rs.getString(1), new StatRow(rs.getString(2), 0, 0));
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(orders)) {
                ps.setString(1, Order.STATUS_CANCELLED);
                ps.setDate(2, from);
                ps.setDate(3, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String id = rs.getString(1);
                        map.computeIfAbsent(id, k -> new StatRow(k, 0, 0)).setCount(rs.getLong(2));
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(revenue)) {
                ps.setString(1, TableSession.STATUS_OPEN);
                ps.setDate(2, from);
                ps.setDate(3, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String id = rs.getString(1);
                        map.computeIfAbsent(id, k -> new StatRow(names.getOrDefault(k, k), 0, 0)).setAmount(rs.getLong(2));
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[StatisticsDao] Lỗi thống kê theo bàn: " + e.getMessage());
        }
        return new ArrayList<>(map.values());
    }

    /** Theo nhân viên phục vụ: count = số lượt bàn đã phục vụ, amount = doanh thu các lượt đó. */
    public static List<StatRow> getByWaiter(Date from, Date to) {
        String sql = "SELECT COALESCE(w.full_name, '(Chưa gán phục vụ)') AS name, COUNT(*) AS n, SUM(s.total_paid) AS amount"
                + " FROM table_sessions s LEFT JOIN staff w ON w.staff_id = s.waiter_id"
                + " WHERE s.status = ? AND DATE(s.closed_at) BETWEEN ? AND ?"
                + " GROUP BY s.waiter_id, w.full_name ORDER BY amount DESC";
        List<StatRow> list = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, TableSession.STATUS_PAID);
            ps.setDate(2, from);
            ps.setDate(3, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new StatRow(rs.getString(1), rs.getLong(2), rs.getLong(3)));
                }
            }
        } catch (SQLException e) {
            System.err.println("[StatisticsDao] Lỗi thống kê theo nhân viên: " + e.getMessage());
        }
        return list;
    }
}
