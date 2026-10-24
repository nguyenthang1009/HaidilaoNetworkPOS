package com.haidilao.pos.database;

import com.haidilao.pos.model.DiningTable;
import com.haidilao.pos.model.Order;
import com.haidilao.pos.model.TableSession;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Truy vấn bảng table_sessions (lượt phục vụ tại bàn: khách, nhân viên phục vụ, thanh toán)
 */
public final class SessionDao {
    private static final String SELECT = "SELECT s.session_id, s.table_id, s.customer_name, s.customer_phone, s.guest_count,"
            + " s.waiter_id, w.full_name AS waiter_name, s.note, s.status, s.opened_at,"
            + " s.subtotal, s.discount, s.total_paid, s.payment_method, s.deposit, s.pay_request"
            + " FROM table_sessions s LEFT JOIN staff w ON w.staff_id = s.waiter_id";

    private SessionDao() {}

    /** Phiên đang phục vụ của bàn, null nếu bàn đang trống */
    public static TableSession getOpenSession(String tableId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return getOpenSession(conn, tableId);
        } catch (SQLException e) {
            System.err.println("[SessionDao] Lỗi đọc phiên của bàn: " + e.getMessage());
            return null;
        }
    }

    static TableSession getOpenSession(Connection conn, String tableId) throws SQLException {
        String sql = SELECT + " WHERE s.table_id = ? AND s.status = ? ORDER BY s.session_id DESC LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableId);
            ps.setString(2, TableSession.STATUS_OPEN);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        }
    }

    public static TableSession getSession(int sessionId) {
        String sql = SELECT + " WHERE s.session_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        } catch (SQLException e) {
            System.err.println("[SessionDao] Lỗi đọc phiên: " + e.getMessage());
            return null;
        }
    }

    private static TableSession read(ResultSet rs) throws SQLException {
        TableSession s = new TableSession(
                rs.getInt("session_id"),
                rs.getString("table_id"),
                rs.getString("customer_name"),
                rs.getString("customer_phone"),
                rs.getInt("guest_count"),
                rs.getInt("waiter_id"),
                rs.getString("waiter_name"),
                rs.getString("note"),
                rs.getString("status"),
                rs.getTimestamp("opened_at")
        );
        s.setPayment(rs.getLong("subtotal"), rs.getLong("discount"), rs.getLong("total_paid"), rs.getString("payment_method"));
        s.setDeposit(rs.getLong("deposit"));
        s.setPayRequest(rs.getString("pay_request"));
        return s;
    }

    /**
     * Lấy phiên đang mở của bàn, chưa có thì mở phiên mới và chuyển bàn sang ĐANG PHỤC VỤ.
     * Gọi bên trong transaction của người gọi (conn đang tắt autocommit).
     */
    static int getOrOpenSession(Connection conn, String tableId) throws SQLException {
        // Khóa dòng của bàn để 2 yêu cầu cùng lúc (Tablet gọi món + Lễ tân mở bàn) không tạo 2 phiên
        lockTable(conn, tableId);
        TableSession open = getOpenSession(conn, tableId);
        if (open != null) return open.getSessionId();

        int id;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO table_sessions (table_id, status) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, tableId);
            ps.setString(2, TableSession.STATUS_OPEN);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                id = rs.getInt(1);
            }
        }
        setTableStatus(conn, tableId, DiningTable.STATUS_SERVING);
        return id;
    }

    /**
     * Lượt khách đang mở của bàn (khóa dòng bàn trong transaction của người gọi). Chưa mở bàn thì báo lỗi,
     * để Tablet không bao giờ tự tạo lượt khách mới: khách phải được Lễ tân xếp bàn trước.
     */
    static int requireOpenSession(Connection conn, String tableId) throws SQLException {
        lockTable(conn, tableId);
        TableSession open = getOpenSession(conn, tableId);
        if (open == null) throw new SQLException("Bàn " + tableId + " chưa được Lễ tân mở");
        return open.getSessionId();
    }

    /**
     * Lễ tân mở bàn / sửa thông tin khách. Bàn chưa có phiên thì mở phiên mới.
     * waiterId = 0: chưa gán nhân viên phục vụ.
     */
    public static TableSession saveSessionInfo(String tableId, String customerName, String customerPhone,
                                               int guestCount, int waiterId, String note) {
        String sql = "UPDATE table_sessions SET customer_name = ?, customer_phone = ?, guest_count = ?, waiter_id = ?, note = ? WHERE session_id = ?";
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int sessionId = getOrOpenSession(conn, tableId);
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, customerName);
                    ps.setString(2, customerPhone);
                    ps.setInt(3, Math.max(0, guestCount));
                    if (waiterId > 0) ps.setInt(4, waiterId); else ps.setNull(4, Types.INTEGER);
                    ps.setString(5, note);
                    ps.setInt(6, sessionId);
                    ps.executeUpdate();
                }
                conn.commit();
                return getOpenSession(conn, tableId);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            System.err.println("[SessionDao] Lỗi lưu thông tin khách: " + e.getMessage());
            return null;
        }
    }

    /**
     * Khách đặt trước đã đến: mở phiên mới ở bàn đã chọn, chép thông tin khách, tiền cọc.
     * Bàn đang có khách khác thì báo lỗi (trả về null, lý do ghi vào error[0]).
     */
    public static TableSession checkIn(String tableId, String customerName, String customerPhone, int guestCount,
                                       int waiterId, String note, long deposit, int reservationId, String[] error) {
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                lockTable(conn, tableId);
                if (getOpenSession(conn, tableId) != null) {
                    conn.rollback();
                    error[0] = "Bàn này đang có khách, hãy chọn bàn khác.";
                    return null;
                }
                int sessionId = getOrOpenSession(conn, tableId);
                String sql = "UPDATE table_sessions SET customer_name = ?, customer_phone = ?, guest_count = ?, waiter_id = ?,"
                        + " note = ?, deposit = ?, reservation_id = ? WHERE session_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, customerName);
                    ps.setString(2, customerPhone);
                    ps.setInt(3, guestCount);
                    if (waiterId > 0) ps.setInt(4, waiterId); else ps.setNull(4, Types.INTEGER);
                    ps.setString(5, note);
                    ps.setLong(6, deposit);
                    if (reservationId > 0) ps.setInt(7, reservationId); else ps.setNull(7, Types.INTEGER);
                    ps.setInt(8, sessionId);
                    ps.executeUpdate();
                }
                conn.commit();
                return getOpenSession(conn, tableId);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            System.err.println("[SessionDao] Lỗi nhận bàn: " + e.getMessage());
            error[0] = "Không lưu được vào database.";
            return null;
        }
    }

    /**
     * Chuyển bàn: phiên đang mở của bàn fromTable (cùng các đơn) sang bàn trống toTable.
     * Bàn cũ chuyển CẦN DỌN, bàn mới ĐANG PHỤC VỤ. Trả về null nếu thành công, ngược lại là lý do.
     */
    public static String moveSession(String fromTable, String toTable) {
        if (fromTable.equals(toTable)) return "Bàn đích trùng bàn hiện tại.";
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Khóa 2 bàn theo thứ tự tên để 2 yêu cầu chuyển ngược chiều nhau không bị deadlock
                String first = fromTable.compareTo(toTable) < 0 ? fromTable : toTable;
                lockTable(conn, first);
                lockTable(conn, first.equals(fromTable) ? toTable : fromTable);
                TableSession from = getOpenSession(conn, fromTable);
                if (from == null) {
                    conn.rollback();
                    return "Bàn hiện tại không có khách để chuyển.";
                }
                if (getOpenSession(conn, toTable) != null) {
                    conn.rollback();
                    return "Bàn đích đang có khách.";
                }
                try (PreparedStatement ps = conn.prepareStatement("UPDATE table_sessions SET table_id = ? WHERE session_id = ?")) {
                    ps.setString(1, toTable);
                    ps.setInt(2, from.getSessionId());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("UPDATE orders SET table_id = ? WHERE session_id = ?")) {
                    ps.setString(1, toTable);
                    ps.setInt(2, from.getSessionId());
                    ps.executeUpdate();
                }
                setTableStatus(conn, fromTable, DiningTable.STATUS_CLEANING);
                setTableStatus(conn, toTable, DiningTable.STATUS_SERVING);
                conn.commit();
                return null;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            System.err.println("[SessionDao] Lỗi chuyển bàn: " + e.getMessage());
            return e.getMessage().contains("không tồn tại") ? e.getMessage() : "Không lưu được vào database.";
        }
    }

    /** Ghi nhận khách bấm "Thanh toán" trên Tablet với hình thức đã chọn */
    public static boolean setPayRequest(int sessionId, String method) {
        String sql = "UPDATE table_sessions SET pay_request = ?, pay_requested_at = CURRENT_TIMESTAMP WHERE session_id = ? AND status = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, method);
            ps.setInt(2, sessionId);
            ps.setString(3, TableSession.STATUS_OPEN);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[SessionDao] Lỗi ghi yêu cầu thanh toán: " + e.getMessage());
            return false;
        }
    }

    private static void lockTable(Connection conn, String tableId) throws SQLException {
        try (PreparedStatement lock = conn.prepareStatement("SELECT table_id FROM dining_tables WHERE table_id = ? FOR UPDATE")) {
            lock.setString(1, tableId);
            try (ResultSet rs = lock.executeQuery()) {
                if (!rs.next()) throw new SQLException("Bàn " + tableId + " không tồn tại");
            }
        }
    }

    /**
     * STT các đơn chưa hủy của phiên, lọc thêm theo trạng thái nếu có (vd. đơn chưa mang ra).
     * Dùng để chặn "Hủy bàn" khi khách đã gọi món, và chặn thanh toán khi còn món chưa mang ra.
     */
    public static List<Integer> getOrderNos(int sessionId, String... statuses) {
        StringBuilder sql = new StringBuilder("SELECT order_no FROM orders WHERE session_id = ? AND order_status <> ?");
        if (statuses.length > 0) {
            sql.append(" AND order_status IN (");
            for (int i = 0; i < statuses.length; i++) sql.append(i == 0 ? "?" : ", ?");
            sql.append(")");
        }
        sql.append(" ORDER BY order_no");
        List<Integer> list = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setInt(1, sessionId);
            ps.setString(2, Order.STATUS_CANCELLED);
            for (int i = 0; i < statuses.length; i++) ps.setString(3 + i, statuses[i]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(rs.getInt(1));
            }
        } catch (SQLException e) {
            System.err.println("[SessionDao] Lỗi đọc đơn của phiên: " + e.getMessage());
        }
        return list;
    }

    /**
     * Hủy bàn: khách mở bàn rồi về khi CHƯA gọi món (hoặc mọi đơn đã hủy). Phiên chuyển ĐÃ HỦY, bàn về TRỐNG.
     * Tiền cọc (nếu có) được giữ lại, ghi vào total_paid để vẫn tính vào doanh thu.
     * Điều kiện "không có đơn" kiểm tra lại trong cùng transaction (đã khóa dòng bàn) nên không lọt đơn gửi lên đúng lúc bấm hủy.
     * Trả về null nếu thành công, ngược lại là lý do.
     */
    public static String cancelSession(String tableId, String reason, int cashierId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                lockTable(conn, tableId);
                TableSession s = getOpenSession(conn, tableId);
                if (s == null) {
                    conn.rollback();
                    return "Bàn này không có khách để hủy.";
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM orders WHERE session_id = ? AND order_status <> ?")) {
                    ps.setInt(1, s.getSessionId());
                    ps.setString(2, Order.STATUS_CANCELLED);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        if (rs.getInt(1) > 0) {
                            conn.rollback();
                            return "Bàn đã gọi món nên không hủy được. Hãy thanh toán (hoặc hủy từng đơn nếu khách không dùng) trước.";
                        }
                    }
                }
                String sql = "UPDATE table_sessions SET status = ?, closed_at = CURRENT_TIMESTAMP, subtotal = 0, discount = 0,"
                        + " total_paid = ?, payment_method = ?, cashier_id = ?, note = CONCAT(COALESCE(note, ''), ?) WHERE session_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, TableSession.STATUS_CANCELLED);
                    ps.setLong(2, s.getDeposit());
                    ps.setString(3, s.getDeposit() > 0 ? "Giữ tiền cọc" : null);
                    if (cashierId > 0) ps.setInt(4, cashierId); else ps.setNull(4, Types.INTEGER);
                    ps.setString(5, " [Hủy bàn: " + (reason == null || reason.isEmpty() ? "khách về" : reason) + "]");
                    ps.setInt(6, s.getSessionId());
                    ps.executeUpdate();
                }
                setTableStatus(conn, tableId, DiningTable.STATUS_EMPTY);
                conn.commit();
                return null;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            System.err.println("[SessionDao] Lỗi hủy bàn: " + e.getMessage());
            return "Không lưu được vào database.";
        }
    }

    /** Tổng tiền các đơn (trừ đơn đã hủy) của một phiên */
    public static long getSubtotal(int sessionId) {
        String sql = "SELECT COALESCE(SUM(total_amount), 0) FROM orders WHERE session_id = ? AND order_status <> ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, sessionId);
            ps.setString(2, Order.STATUS_CANCELLED);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            System.err.println("[SessionDao] Lỗi tính tiền phiên: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Thanh toán: đóng phiên, lưu số tiền, bàn chuyển sang CẦN DỌN. cashierId = 0 nếu không rõ.
     */
    public static boolean closeSession(int sessionId, String tableId, long subtotal, long discount, long totalPaid,
                                       String paymentMethod, int cashierId) {
        String sql = "UPDATE table_sessions SET status = ?, closed_at = CURRENT_TIMESTAMP, subtotal = ?, discount = ?,"
                + " total_paid = ?, payment_method = ?, cashier_id = ? WHERE session_id = ? AND status = ?";
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int n;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, TableSession.STATUS_PAID);
                    ps.setLong(2, subtotal);
                    ps.setLong(3, discount);
                    ps.setLong(4, totalPaid);
                    ps.setString(5, paymentMethod);
                    if (cashierId > 0) ps.setInt(6, cashierId); else ps.setNull(6, Types.INTEGER);
                    ps.setInt(7, sessionId);
                    ps.setString(8, TableSession.STATUS_OPEN);
                    n = ps.executeUpdate();
                }
                if (n > 0) setTableStatus(conn, tableId, DiningTable.STATUS_CLEANING);
                conn.commit();
                return n > 0;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            System.err.println("[SessionDao] Lỗi thanh toán: " + e.getMessage());
            return false;
        }
    }

    private static void setTableStatus(Connection conn, String tableId, String status) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE dining_tables SET status = ? WHERE table_id = ?")) {
            ps.setString(1, status);
            ps.setString(2, tableId);
            ps.executeUpdate();
        }
    }
}
