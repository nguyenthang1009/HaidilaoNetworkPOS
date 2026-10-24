package com.haidilao.pos.model;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;

/**
 * Lượt đặt bàn trước của khách (gọi điện hoặc đến quầy đặt)
 */
public class Reservation {
    public static final String STATUS_BOOKED = "ĐÃ ĐẶT";
    public static final String STATUS_CHECKED_IN = "ĐÃ NHẬN BÀN";
    public static final String STATUS_CANCELLED = "ĐÃ HỦY";
    public static final String STATUS_NO_SHOW = "KHÔNG ĐẾN";

    /** Định dạng ngày giờ dùng trong giao thức: 2026-10-24 19:30 */
    public static final String DATE_TIME_FORMAT = "yyyy-MM-dd HH:mm";

    private int reservationId;
    private String customerName;
    private String customerPhone;
    private int guestCount;
    private Timestamp reservedAt;
    private int durationMinutes;
    private String tableId;       // null = chưa chọn bàn
    private String tableName;
    private String areaPref;
    private long deposit;
    private String note;
    private String status;
    private int sessionId;

    public Reservation(int reservationId, String customerName, String customerPhone, int guestCount, Timestamp reservedAt,
                       int durationMinutes, String tableId, String areaPref, long deposit, String note, String status) {
        this.reservationId = reservationId;
        this.customerName = customerName;
        this.customerPhone = customerPhone;
        this.guestCount = guestCount;
        this.reservedAt = reservedAt;
        this.durationMinutes = durationMinutes;
        this.tableId = (tableId == null || tableId.isEmpty()) ? null : tableId;
        this.areaPref = areaPref;
        this.deposit = deposit;
        this.note = note;
        this.status = status;
    }

    public int getReservationId() {
        return reservationId;
    }

    public void setReservationId(int reservationId) {
        this.reservationId = reservationId;
    }

    public String getCustomerName() {
        return customerName == null ? "" : customerName;
    }

    public String getCustomerPhone() {
        return customerPhone == null ? "" : customerPhone;
    }

    public int getGuestCount() {
        return guestCount;
    }

    public Timestamp getReservedAt() {
        return reservedAt;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public String getTableId() {
        return tableId;
    }

    public String getTableName() {
        return tableName == null ? (tableId == null ? "" : tableId) : tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getAreaPref() {
        return areaPref == null ? "" : areaPref;
    }

    public long getDeposit() {
        return deposit;
    }

    public String getNote() {
        return note == null ? "" : note;
    }

    public String getStatus() {
        return status;
    }

    public int getSessionId() {
        return sessionId;
    }

    public void setSessionId(int sessionId) {
        this.sessionId = sessionId;
    }

    public long getEndMillis() {
        return reservedAt.getTime() + durationMinutes * 60_000L;
    }

    public String getTimeText() {
        return new SimpleDateFormat("HH:mm").format(reservedAt);
    }

    public String getDateTimeText() {
        return new SimpleDateFormat(DATE_TIME_FORMAT).format(reservedAt);
    }
}
