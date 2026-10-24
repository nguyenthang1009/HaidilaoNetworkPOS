package com.haidilao.pos.database;

import com.haidilao.pos.model.Reservation;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Truy vấn bảng reservations (đặt bàn trước)
 */
public final class ReservationDao {
    private static final String SELECT = "SELECT r.reservation_id, r.customer_name, r.customer_phone, r.guest_count, r.reserved_at,"
            + " r.duration_minutes, r.table_id, t.table_name, r.area_pref, r.deposit, r.note, r.status, r.session_id"
            + " FROM reservations r LEFT JOIN dining_tables t ON t.table_id = r.table_id";

    private ReservationDao() {}

    /** Các lượt đặt của một ngày (mọi trạng thái), sắp theo giờ */
    public static List<Reservation> getByDate(Date day) {
        return query(SELECT + " WHERE DATE(r.reserved_at) = ? ORDER BY r.reserved_at, r.reservation_id", ps -> ps.setDate(1, day));
    }

    public static Reservation get(int id) {
        List<Reservation> list = query(SELECT + " WHERE r.reservation_id = ?", ps -> ps.setInt(1, id));
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * Lượt ĐÃ ĐẶT sớm nhất của từng bàn, có giờ hẹn trong khoảng [bây giờ - lateMinutes, bây giờ + aheadMinutes].
     * Dùng để hiện "Đặt 19:00" trên thẻ bàn và cảnh báo khi xếp khách vãng lai.
     */
    public static Map<String, Reservation> getUpcomingByTable(int lateMinutes, int aheadMinutes) {
        Map<String, Reservation> map = new LinkedHashMap<>();
        String sql = SELECT + " WHERE r.status = ? AND r.table_id IS NOT NULL"
                + " AND r.reserved_at BETWEEN NOW() - INTERVAL ? MINUTE AND NOW() + INTERVAL ? MINUTE ORDER BY r.reserved_at";
        for (Reservation r : query(sql, ps -> {
            ps.setString(1, Reservation.STATUS_BOOKED);
            ps.setInt(2, lateMinutes);
            ps.setInt(3, aheadMinutes);
        })) {
            map.putIfAbsent(r.getTableId(), r);
        }
        return map;
    }

    /**
     * Có lượt ĐÃ ĐẶT khác của cùng bàn trùng khung giờ [start, start + duration) không.
     */
    public static Reservation findConflict(String tableId, Timestamp start, int durationMinutes, int excludeId) {
        String sql = SELECT + " WHERE r.table_id = ? AND r.status = ? AND r.reservation_id <> ?"
                + " AND r.reserved_at < ? AND DATE_ADD(r.reserved_at, INTERVAL r.duration_minutes MINUTE) > ? ORDER BY r.reserved_at LIMIT 1";
        Timestamp end = new Timestamp(start.getTime() + durationMinutes * 60_000L);
        List<Reservation> list = query(sql, ps -> {
            ps.setString(1, tableId);
            ps.setString(2, Reservation.STATUS_BOOKED);
            ps.setInt(3, excludeId);
            ps.setTimestamp(4, end);
            ps.setTimestamp(5, start);
        });
        return list.isEmpty() ? null : list.get(0);
    }

    /** Thêm (reservationId = 0) hoặc sửa lượt đặt. Trả về mã lượt đặt, -1 nếu lỗi. */
    public static int save(Reservation r, int staffId) {
        boolean isNew = r.getReservationId() <= 0;
        String sql = isNew
                ? "INSERT INTO reservations (customer_name, customer_phone, guest_count, reserved_at, duration_minutes, table_id,"
                    + " area_pref, deposit, note, status, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                : "UPDATE reservations SET customer_name = ?, customer_phone = ?, guest_count = ?, reserved_at = ?, duration_minutes = ?,"
                    + " table_id = ?, area_pref = ?, deposit = ?, note = ? WHERE reservation_id = ? AND status = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, r.getCustomerName());
            ps.setString(2, r.getCustomerPhone());
            ps.setInt(3, r.getGuestCount());
            ps.setTimestamp(4, r.getReservedAt());
            ps.setInt(5, r.getDurationMinutes());
            ps.setString(6, r.getTableId());
            ps.setString(7, r.getAreaPref());
            ps.setLong(8, r.getDeposit());
            ps.setString(9, r.getNote());
            if (isNew) {
                ps.setString(10, Reservation.STATUS_BOOKED);
                if (staffId > 0) ps.setInt(11, staffId); else ps.setNull(11, Types.INTEGER);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
            ps.setInt(10, r.getReservationId());
            ps.setString(11, Reservation.STATUS_BOOKED);
            return ps.executeUpdate() > 0 ? r.getReservationId() : -1;
        } catch (SQLException e) {
            System.err.println("[ReservationDao] Lỗi lưu lượt đặt: " + e.getMessage());
            return -1;
        }
    }

    /** Đổi trạng thái lượt đang ĐÃ ĐẶT sang ĐÃ HỦY / KHÔNG ĐẾN / ĐÃ NHẬN BÀN */
    public static boolean setStatus(int id, String status, int sessionId) {
        String sql = "UPDATE reservations SET status = ?, session_id = ? WHERE reservation_id = ? AND status = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            if (sessionId > 0) ps.setInt(2, sessionId); else ps.setNull(2, Types.INTEGER);
            ps.setInt(3, id);
            ps.setString(4, Reservation.STATUS_BOOKED);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[ReservationDao] Lỗi đổi trạng thái lượt đặt: " + e.getMessage());
            return false;
        }
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private static List<Reservation> query(String sql, Binder binder) {
        List<Reservation> list = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Reservation r = new Reservation(
                            rs.getInt("reservation_id"),
                            rs.getString("customer_name"),
                            rs.getString("customer_phone"),
                            rs.getInt("guest_count"),
                            rs.getTimestamp("reserved_at"),
                            rs.getInt("duration_minutes"),
                            rs.getString("table_id"),
                            rs.getString("area_pref"),
                            rs.getLong("deposit"),
                            rs.getString("note"),
                            rs.getString("status"));
                    r.setTableName(rs.getString("table_name"));
                    r.setSessionId(rs.getInt("session_id"));
                    list.add(r);
                }
            }
        } catch (SQLException e) {
            System.err.println("[ReservationDao] Lỗi đọc lượt đặt: " + e.getMessage());
        }
        return list;
    }
}
