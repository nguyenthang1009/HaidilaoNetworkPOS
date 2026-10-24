package com.haidilao.pos.model;

import java.sql.Timestamp;

/**
 * Một lượt phục vụ tại bàn: từ lúc mở bàn (hoặc khách gọi món đầu tiên) đến lúc thanh toán.
 * Lưu thông tin khách, nhân viên phục vụ, ghi chú và số tiền đã thanh toán.
 */
public class TableSession {
    public static final String STATUS_OPEN = "ĐANG PHỤC VỤ";
    public static final String STATUS_PAID = "ĐÃ THANH TOÁN";
    public static final String STATUS_CANCELLED = "ĐÃ HỦY";   // khách về khi chưa gọi món (Lễ tân bấm "Hủy bàn")

    private int sessionId;
    private String tableId;
    private String customerName;
    private String customerPhone;
    private int guestCount;
    private int waiterId;          // 0 = chưa gán
    private String waiterName;
    private String note;
    private String status;
    private Timestamp openedAt;
    private long subtotal;
    private long discount;
    private long totalPaid;
    private String paymentMethod;
    private long deposit;          // tiền cọc khi đặt bàn trước, trừ vào lúc thanh toán
    private String payRequest;     // hình thức thanh toán khách yêu cầu từ Tablet (null = chưa yêu cầu)

    public TableSession(int sessionId, String tableId, String customerName, String customerPhone, int guestCount,
                        int waiterId, String waiterName, String note, String status, Timestamp openedAt) {
        this.sessionId = sessionId;
        this.tableId = tableId;
        this.customerName = customerName;
        this.customerPhone = customerPhone;
        this.guestCount = guestCount;
        this.waiterId = waiterId;
        this.waiterName = waiterName;
        this.note = note;
        this.status = status;
        this.openedAt = openedAt;
    }

    public int getSessionId() {
        return sessionId;
    }

    public String getTableId() {
        return tableId;
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

    public int getWaiterId() {
        return waiterId;
    }

    public String getWaiterName() {
        return waiterName == null ? "" : waiterName;
    }

    public String getNote() {
        return note == null ? "" : note;
    }

    public String getStatus() {
        return status;
    }

    public Timestamp getOpenedAt() {
        return openedAt;
    }

    public long getSubtotal() {
        return subtotal;
    }

    public long getDiscount() {
        return discount;
    }

    public long getTotalPaid() {
        return totalPaid;
    }

    public String getPaymentMethod() {
        return paymentMethod == null ? "" : paymentMethod;
    }

    public long getDeposit() {
        return deposit;
    }

    public void setDeposit(long deposit) {
        this.deposit = deposit;
    }

    public String getPayRequest() {
        return payRequest == null ? "" : payRequest;
    }

    public void setPayRequest(String payRequest) {
        this.payRequest = payRequest;
    }

    public void setPayment(long subtotal, long discount, long totalPaid, String paymentMethod) {
        this.subtotal = subtotal;
        this.discount = discount;
        this.totalPaid = totalPaid;
        this.paymentMethod = paymentMethod;
    }
}
