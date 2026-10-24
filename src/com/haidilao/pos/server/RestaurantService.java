package com.haidilao.pos.server;

import com.haidilao.pos.database.ComboDao;
import com.haidilao.pos.database.DatabaseManager;
import com.haidilao.pos.database.OrderDao;
import com.haidilao.pos.database.ReservationDao;
import com.haidilao.pos.database.SessionDao;
import com.haidilao.pos.database.SettingsDao;
import com.haidilao.pos.database.TableDao;
import com.haidilao.pos.model.Combo;
import com.haidilao.pos.model.DiningTable;
import com.haidilao.pos.model.MenuItem;
import com.haidilao.pos.model.Order;
import com.haidilao.pos.model.OrderLine;
import com.haidilao.pos.model.Reservation;
import com.haidilao.pos.model.TableSession;
import com.haidilao.pos.protocol.NetworkPacket;
import com.haidilao.pos.protocol.ProtocolConstants;

import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nghiệp vụ nhà hàng phía Server: kiểm tra và tính tiền đơn gọi món, thanh toán,
 * và tạo các bản tin gửi cho Lễ tân / Bếp. ClientSession chỉ lo nhận - gửi bản tin, phần xử lý nằm ở đây.
 */
public final class RestaurantService {
    public static final String PAY_CASH = "Tiền mặt";
    public static final String PAY_TRANSFER = "Chuyển khoản";
    public static final String[] PAYMENT_METHODS = {PAY_CASH, PAY_TRANSFER};
    /** Khách đặt trước được nhận bàn sớm nhất bao nhiêu phút trước giờ hẹn */
    public static final int CHECK_IN_EARLY_MINUTES = 120;

    private RestaurantService() {}

    /** Kết quả xử lý một yêu cầu: thành công thì có value, thất bại thì có error */
    public static final class Result<T> {
        public final T value;
        public final String error;

        private Result(T value, String error) {
            this.value = value;
            this.error = error;
        }

        static <T> Result<T> ok(T value) {
            return new Result<>(value, null);
        }

        static <T> Result<T> fail(String error) {
            return new Result<>(null, error);
        }

        public boolean isOk() {
            return error == null;
        }
    }

    /**
     * Tablet gọi món. Server KHÔNG tin giá / tên do Tablet gửi: tra lại từng món, combo trong database,
     * món ngừng bán thì từ chối, rồi tự tính tổng tiền.
     */
    public static Result<Order> placeOrder(String tableId, String note, List<OrderLine> requested) {
        if (requested.isEmpty()) {
            return Result.fail("Đơn hàng trống hoặc sai định dạng.");
        }
        if (requested.size() > ProtocolConstants.MAX_ORDER_LINES) {
            return Result.fail("Đơn hàng quá nhiều dòng (tối đa " + ProtocolConstants.MAX_ORDER_LINES + ").");
        }
        DiningTable table = TableDao.getTable(tableId);
        if (table == null) {
            return Result.fail("Bàn " + tableId + " chưa có trong danh sách bàn của nhà hàng.");
        }
        if (SessionDao.getOpenSession(tableId) == null) {
            // Khách phải ra quầy Lễ tân để được xếp bàn (ghi tên, số khách) trước, Tablet không tự mở bàn
            if (DiningTable.STATUS_CLEANING.equals(table.getStatus())) {
                return Result.fail("Bàn đang chờ dọn. Quý khách vui lòng đến quầy Lễ tân để được xếp bàn.");
            }
            Reservation upcoming = upcomingReservations().get(tableId);
            if (upcoming != null) {
                return Result.fail("Bàn này đã có khách đặt lúc " + upcoming.getTimeText()
                        + ". Quý khách vui lòng đến quầy Lễ tân để được xếp bàn.");
            }
            return Result.fail("Bàn chưa được mở. Quý khách vui lòng đến quầy Lễ tân để được xếp bàn trước khi gọi món.");
        }

        List<OrderLine> lines = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        long total = 0;
        for (OrderLine r : requested) {
            if (r.getQuantity() > ProtocolConstants.MAX_LINE_QUANTITY) {
                return Result.fail("Số lượng mỗi món tối đa là " + ProtocolConstants.MAX_LINE_QUANTITY + ".");
            }
            String name;
            long price;
            if (r.isCombo()) {
                Combo c = ComboDao.getCombo(r.getRefId());
                if (c == null || !"AVAILABLE".equals(c.getStatus())) {
                    return Result.fail("Combo mã " + r.getRefId() + " không còn bán. Vui lòng tải lại thực đơn.");
                }
                for (Combo.ComboItem ci : c.getItems()) {
                    MenuItem part = DatabaseManager.getMenuItemById(ci.getItemId());
                    if (part == null || !"AVAILABLE".equals(part.getStatus())) {
                        return Result.fail(c.getComboName() + " tạm hết vì món " + ci.getItemName() + " đã hết. Quý khách vui lòng chọn món khác.");
                    }
                }
                name = c.getComboName();
                price = c.getPrice();
            } else {
                MenuItem m = DatabaseManager.getMenuItemById(r.getRefId());
                if (m == null || !"AVAILABLE".equals(m.getStatus())) {
                    return Result.fail("Món mã " + r.getRefId() + " đã hết hoặc ngừng bán. Vui lòng tải lại thực đơn.");
                }
                name = m.getItemName();
                price = m.getPrice();
            }
            String lineNote = limit(r.getNote().trim());
            OrderLine line = new OrderLine(r.getType(), r.getRefId(), name, r.getQuantity(), price, lineNote);
            lines.add(line);
            total += line.getAmount();
            if (summary.length() > 0) summary.append(", ");
            summary.append(name).append(" x").append(r.getQuantity());
            if (!lineNote.isEmpty()) summary.append(" (").append(lineNote).append(")");
        }

        Order order = OrderDao.insertOrder(tableId, limit(note == null ? "" : note.trim()), lines, summary.toString(), total);
        return (order == null) ? Result.fail("Server không lưu được đơn hàng, vui lòng thử lại.") : Result.ok(order);
    }

    /**
     * Thanh toán bàn: tính tổng các đơn chưa hủy, trừ giảm giá, đóng phiên, bàn chuyển sang CẦN DỌN.
     */
    public static Result<String> checkout(String tableId, long discount, String paymentMethod, int cashierId) {
        TableSession s = SessionDao.getOpenSession(tableId);
        if (s == null) {
            return Result.fail("Bàn này chưa có khách (chưa mở bàn), không có gì để thanh toán.");
        }
        boolean validMethod = false;
        for (String m : PAYMENT_METHODS) {
            if (m.equals(paymentMethod)) validMethod = true;
        }
        if (!validMethod) {
            return Result.fail("Hình thức thanh toán không hợp lệ.");
        }
        long subtotal = SessionDao.getSubtotal(s.getSessionId());
        if (subtotal <= 0) {
            return Result.fail("Bàn chưa gọi món nào (hoặc mọi đơn đã hủy) nên không có gì để thanh toán."
                    + " Nếu khách về, hãy dùng \"Hủy bàn\".");
        }
        List<Integer> unserved = SessionDao.getOrderNos(s.getSessionId(), Order.STATUS_WAITING, Order.STATUS_COOKING, Order.STATUS_DONE);
        if (!unserved.isEmpty()) {
            StringBuilder nos = new StringBuilder();
            for (int no : unserved) nos.append(nos.length() > 0 ? ", " : "").append(Order.formatOrderNo(no));
            return Result.fail("Còn đơn chưa mang ra cho khách: " + nos
                    + ". Hãy đánh dấu \"Đã mang món ra\" (hoặc hủy đơn khách không dùng) rồi mới thanh toán.");
        }
        if (discount < 0 || discount > subtotal) {
            return Result.fail("Số tiền giảm giá phải từ 0 đến " + subtotal + ".");
        }
        long totalPaid = subtotal - discount;   // doanh thu của lượt (đã gồm tiền cọc)
        if (!SessionDao.closeSession(s.getSessionId(), tableId, subtotal, discount, totalPaid, paymentMethod, cashierId)) {
            return Result.fail("Bàn đã được thanh toán ở quầy khác hoặc lỗi lưu database.");
        }
        DiningTable t = TableDao.getTable(tableId);
        return Result.ok(buildBill(t, s, subtotal, discount, totalPaid, paymentMethod));
    }

    /** Các dòng hóa đơn của một lượt: gộp món giống nhau, bỏ đơn đã hủy. Mỗi dòng "tên<TAB>SL<TAB>đơn giá<TAB>thành tiền" */
    private static String billLines(int sessionId) {
        Map<String, long[]> merged = new LinkedHashMap<>();
        for (Order o : OrderDao.getOrdersBySession(sessionId)) {
            if (Order.STATUS_CANCELLED.equals(o.getOrderStatus())) continue;
            for (OrderLine l : o.getLines()) {
                long[] v = merged.computeIfAbsent(l.getName() + "\t" + l.getUnitPrice(), k -> new long[2]);
                v[0] += l.getQuantity();
                v[1] += l.getAmount();
            }
        }
        StringBuilder items = new StringBuilder();
        for (Map.Entry<String, long[]> e : merged.entrySet()) {
            String[] key = e.getKey().split("\t");
            items.append(key[0]).append('\t').append(e.getValue()[0]).append('\t').append(key[1]).append('\t').append(e.getValue()[1]).append('\n');
        }
        return items.toString();
    }

    /**
     * Hóa đơn tạm tính cho Tablet:
     * BILL_PREVIEW|MÃ_PHIÊN|TẠM_TÍNH|TIỀN_CỌC|CÁC_DÒNG|NGÂN_HÀNG|SỐ_TÀI_KHOẢN|CHỦ_TÀI_KHOẢN|NỘI_DUNG_CK|YÊU_CẦU_ĐÃ_GỬI
     */
    public static String buildBillPreview(String tableId) {
        TableSession s = SessionDao.getOpenSession(tableId);
        if (s == null) return "BILL_PREVIEW|0|0|0||||||";
        long subtotal = SessionDao.getSubtotal(s.getSessionId());
        String content = tableId.replaceAll("[^A-Za-z0-9]", "").toUpperCase().replace("TABLET", "") + " HD" + s.getSessionId();
        return "BILL_PREVIEW|" + s.getSessionId() + "|" + subtotal + "|" + s.getDeposit() + "|" + NetworkPacket.enc(billLines(s.getSessionId()))
                + "|" + NetworkPacket.enc(SettingsDao.get(SettingsDao.BANK_NAME, ""))
                + "|" + NetworkPacket.enc(SettingsDao.get(SettingsDao.BANK_ACCOUNT, ""))
                + "|" + NetworkPacket.enc(SettingsDao.get(SettingsDao.BANK_OWNER, ""))
                + "|" + NetworkPacket.enc(content) + "|" + NetworkPacket.enc(s.getPayRequest());
    }

    // ------------------------------------------------------------------ đặt bàn

    /**
     * Kiểm tra và lưu lượt đặt bàn (thêm mới nếu id = 0). Quy tắc R1-R4 trong tài liệu phân tích:
     * đủ thông tin, không đặt vào giờ đã qua, bàn đủ ghế, không trùng giờ với lượt đặt khác của cùng bàn.
     */
    public static Result<Reservation> saveReservation(int id, String name, String phone, int guests, String dateTime,
                                                      String tableId, String area, long deposit, String note, int staffId) {
        name = name.trim();
        phone = phone.trim();
        if (name.isEmpty() || name.length() > 80) return Result.fail("Vui lòng nhập tên khách (tối đa 80 ký tự).");
        if (!phone.replaceAll("[ .\\-]", "").matches("\\+?\\d{9,11}")) return Result.fail("Số điện thoại phải có 9 - 11 chữ số.");
        if (guests < 1 || guests > 99) return Result.fail("Số khách phải từ 1 đến 99.");
        if (deposit < 0) return Result.fail("Tiền cọc không hợp lệ.");
        if (note.length() > ProtocolConstants.MAX_NOTE_LENGTH) return Result.fail("Ghi chú quá dài.");
        Timestamp at;
        try {
            SimpleDateFormat f = new SimpleDateFormat(Reservation.DATE_TIME_FORMAT);
            f.setLenient(false);
            at = new Timestamp(f.parse(dateTime).getTime());
        } catch (ParseException e) {
            return Result.fail("Ngày giờ không hợp lệ (ngày dd/MM/yyyy, giờ HH:mm).");
        }
        if (at.getTime() < System.currentTimeMillis() - 5 * 60_000L) {
            return Result.fail("Không thể đặt bàn vào thời điểm đã qua.");
        }
        if (id > 0) {
            Reservation old = ReservationDao.get(id);
            if (old == null || !Reservation.STATUS_BOOKED.equals(old.getStatus())) {
                return Result.fail("Lượt đặt không còn ở trạng thái ĐÃ ĐẶT, không sửa được.");
            }
        }
        String table = (tableId == null || tableId.isEmpty()) ? null : tableId;
        if (table != null) {
            DiningTable t = TableDao.getTable(table);
            if (t == null) return Result.fail("Không tìm thấy bàn " + table + ".");
            if (t.getCapacity() < guests) {
                return Result.fail(t.getTableName() + " chỉ có " + t.getCapacity() + " ghế, không đủ cho " + guests + " khách.");
            }
            Reservation c = ReservationDao.findConflict(table, at, ProtocolConstants.RESERVATION_MINUTES, id);
            if (c != null) {
                return Result.fail(t.getTableName() + " đã có khách " + c.getCustomerName() + " đặt lúc " + c.getTimeText()
                        + " (giữ bàn " + ProtocolConstants.RESERVATION_MINUTES / 60 + " giờ). Hãy chọn bàn hoặc giờ khác.");
            }
        }
        Reservation r = new Reservation(id, name, phone, guests, at, ProtocolConstants.RESERVATION_MINUTES, table,
                area, deposit, note.trim(), Reservation.STATUS_BOOKED);
        int savedId = ReservationDao.save(r, staffId);
        if (savedId <= 0) return Result.fail("Không lưu được lượt đặt vào database.");
        return Result.ok(ReservationDao.get(savedId));
    }

    /**
     * Khách đặt trước đã đến: mở bàn (bàn đã đặt, hoặc bàn Lễ tân chọn lúc khách đến), chép thông tin khách.
     */
    public static Result<TableSession> checkIn(int reservationId, String overrideTable, int waiterId) {
        Reservation r = ReservationDao.get(reservationId);
        if (r == null || !Reservation.STATUS_BOOKED.equals(r.getStatus())) {
            return Result.fail("Lượt đặt không còn ở trạng thái ĐÃ ĐẶT.");
        }
        long now = System.currentTimeMillis();
        if (now < r.getReservedAt().getTime() - CHECK_IN_EARLY_MINUTES * 60_000L) {
            return Result.fail("Lượt đặt lúc " + r.getDateTimeText() + " còn quá sớm để nhận bàn (chỉ nhận trước giờ hẹn tối đa "
                    + CHECK_IN_EARLY_MINUTES / 60 + " giờ). Nếu khách đến sớm, hãy xếp bàn như khách vãng lai.");
        }
        if (now > r.getEndMillis()) {
            return Result.fail("Lượt đặt đã quá thời gian giữ bàn, hãy chuyển sang \"Không đến\" rồi xếp bàn như khách vãng lai.");
        }
        String table = (overrideTable != null && !overrideTable.isEmpty()) ? overrideTable : r.getTableId();
        if (table == null) return Result.fail("Lượt đặt chưa chọn bàn, hãy chọn bàn cho khách.");
        DiningTable t = TableDao.getTable(table);
        if (t == null) return Result.fail("Không tìm thấy bàn " + table + ".");
        if (DiningTable.STATUS_CLEANING.equals(t.getStatus())) {
            return Result.fail(t.getTableName() + " chưa được dọn. Bấm \"Đã dọn xong\" hoặc chọn bàn khác.");
        }
        if (t.getCapacity() < r.getGuestCount()) {
            return Result.fail(t.getTableName() + " chỉ có " + t.getCapacity() + " ghế, không đủ cho " + r.getGuestCount() + " khách.");
        }
        String note = "Khách đặt trước lúc " + r.getTimeText() + (r.getNote().isEmpty() ? "" : ". " + r.getNote());
        String[] err = new String[1];
        TableSession s = SessionDao.checkIn(table, r.getCustomerName(), r.getCustomerPhone(), r.getGuestCount(),
                waiterId, note, r.getDeposit(), reservationId, err);
        if (s == null) return Result.fail(err[0]);
        ReservationDao.setStatus(reservationId, Reservation.STATUS_CHECKED_IN, s.getSessionId());
        return Result.ok(s);
    }

    /**
     * RESERVATION|MÃ|TÊN|SĐT|SỐ_KHÁCH|yyyy-MM-dd HH:mm|PHÚT_GIỮ_BÀN|BÀN|TÊN_BÀN|KHU_VỰC|TIỀN_CỌC|GHI_CHÚ|TRẠNG_THÁI
     */
    public static String buildReservation(Reservation r) {
        return "RESERVATION|" + r.getReservationId() + "|" + NetworkPacket.enc(r.getCustomerName()) + "|" + NetworkPacket.enc(r.getCustomerPhone())
                + "|" + r.getGuestCount() + "|" + NetworkPacket.enc(r.getDateTimeText()) + "|" + r.getDurationMinutes()
                + "|" + (r.getTableId() == null ? "" : r.getTableId()) + "|" + NetworkPacket.enc(r.getTableName())
                + "|" + NetworkPacket.enc(r.getAreaPref()) + "|" + r.getDeposit() + "|" + NetworkPacket.enc(r.getNote())
                + "|" + NetworkPacket.enc(r.getStatus());
    }

    /**
     * BILL|BÀN|TÊN_BÀN|MÃ_PHIÊN|TẠM_TÍNH|GIẢM_GIÁ|TỔNG_SAU_GIẢM|HÌNH_THỨC|TÊN_KHÁCH|SỐ_KHÁCH|PHỤC_VỤ|CÁC_DÒNG|TIỀN_CỌC|CÒN_PHẢI_TRẢ
     * CÁC_DÒNG: mỗi dòng "tên<TAB>số lượng<TAB>đơn giá<TAB>thành tiền", cách nhau bằng xuống dòng (đã mã hóa URL)
     */
    private static String buildBill(DiningTable t, TableSession s, long subtotal, long discount, long totalPaid, String method) {
        long due = Math.max(0, totalPaid - s.getDeposit());
        return "BILL|" + s.getTableId() + "|" + NetworkPacket.enc(t != null ? t.getTableName() : s.getTableId()) + "|" + s.getSessionId()
                + "|" + subtotal + "|" + discount + "|" + totalPaid + "|" + NetworkPacket.enc(method)
                + "|" + NetworkPacket.enc(s.getCustomerName()) + "|" + s.getGuestCount() + "|" + NetworkPacket.enc(s.getWaiterName())
                + "|" + NetworkPacket.enc(billLines(s.getSessionId())) + "|" + s.getDeposit() + "|" + due;
    }

    // ------------------------------------------------------------------ bản tin gửi Lễ tân / Bếp / Tablet

    /** NEW_ORDER|MÃ_ĐƠN|STT|BÀN|TÊN_BÀN|TÓM_TẮT|TỔNG_TIỀN|GHI_CHÚ|TRẠNG_THÁI|GIỜ (OLD_ORDER cùng định dạng) */
    public static String buildReceptionOrder(String command, Order o) {
        return command + "|" + o.getOrderId() + "|" + o.getOrderNo() + "|" + o.getTableId()
                + "|" + NetworkPacket.enc(o.getTableName()) + "|" + NetworkPacket.enc(o.getItemsSummary())
                + "|" + o.getTotalAmount() + "|" + NetworkPacket.enc(o.getNote()) + "|" + NetworkPacket.enc(o.getOrderStatus())
                + "|" + o.getShortTime();
    }

    /** KITCHEN_ORDER|MÃ_ĐƠN|STT|TÊN_BÀN|GIỜ|TRẠNG_THÁI|GHI_CHÚ_ĐƠN|CÁC_MÓN (combo được tách ra món bên trong) */
    public static String buildKitchenOrder(Order o) {
        List<String> names = new ArrayList<>();
        Map<Integer, Combo> comboCache = new LinkedHashMap<>();
        for (OrderLine l : o.getLines()) {
            if (l.isCombo()) {
                Combo c = comboCache.computeIfAbsent(l.getRefId(), ComboDao::getCombo);
                String parts = (c == null) ? "" : c.getItemsSummary();
                names.add(parts.isEmpty() ? l.getName() : l.getName() + " (gồm: " + parts + ")");
            } else {
                names.add(l.getName());
            }
        }
        return "KITCHEN_ORDER|" + o.getOrderId() + "|" + o.getOrderNo() + "|" + NetworkPacket.enc(o.getTableName())
                + "|" + o.getShortTime() + "|" + NetworkPacket.enc(o.getOrderStatus()) + "|" + NetworkPacket.enc(o.getNote())
                + "|" + NetworkPacket.encodeKitchenLines(o.getLines(), names);
    }

    /** ORDER_STATUS|MÃ_ĐƠN|STT|TRẠNG_THÁI (gửi Tablet của bàn) */
    public static String buildTabletOrderStatus(Order o) {
        return "ORDER_STATUS|" + o.getOrderId() + "|" + o.getOrderNo() + "|" + NetworkPacket.enc(o.getOrderStatus());
    }

    /**
     * TABLE_INFO|BÀN|TÊN|SỐ_GHẾ|KHU_VỰC|TRẠNG_THÁI|MÃ_PHIÊN|TÊN_KHÁCH|SỐ_KHÁCH|PHỤC_VỤ|ONLINE(1/0)|GIỜ_VÀO|ĐẶT_SẮP_TỚI|YÊU_CẦU_THANH_TOÁN
     * Bàn đang trống mà có lượt đặt trong 60 phút tới thì trạng thái hiện là ĐÃ ĐẶT.
     */
    public static String buildTableInfo(DiningTable t, TableSession s, boolean online, Reservation upcoming) {
        String opened = (s != null && s.getOpenedAt() != null) ? new SimpleDateFormat("HH:mm").format(s.getOpenedAt()) : "";
        String status = t.getStatus();
        String next = "";
        if (upcoming != null) {
            next = upcoming.getTimeText() + " · " + upcoming.getCustomerName() + " · " + upcoming.getGuestCount() + " khách";
            if (s == null && DiningTable.STATUS_EMPTY.equals(status)) status = DiningTable.STATUS_RESERVED;
        }
        return "TABLE_INFO|" + t.getTableId() + "|" + NetworkPacket.enc(t.getTableName()) + "|" + t.getCapacity()
                + "|" + NetworkPacket.enc(t.getArea()) + "|" + NetworkPacket.enc(status)
                + "|" + (s == null ? 0 : s.getSessionId())
                + "|" + NetworkPacket.enc(s == null ? "" : s.getCustomerName())
                + "|" + (s == null ? 0 : s.getGuestCount())
                + "|" + NetworkPacket.enc(s == null ? "" : s.getWaiterName())
                + "|" + (online ? 1 : 0) + "|" + opened + "|" + NetworkPacket.enc(next)
                + "|" + NetworkPacket.enc(s == null ? "" : s.getPayRequest());
    }

    /** Lượt đặt sắp tới của từng bàn (để tính trạng thái ĐÃ ĐẶT) */
    public static Map<String, Reservation> upcomingReservations() {
        return ReservationDao.getUpcomingByTable(ProtocolConstants.RESERVATION_LATE_MINUTES, ProtocolConstants.RESERVATION_HOLD_MINUTES);
    }

    /**
     * SESSION_INFO|TÊN_KHÁCH|SỐ_KHÁCH|PHỤC_VỤ|ĐÃ_MỞ_BÀN(1/0)
     * Gửi Tablet để chào khách, hiện tên nhân viên phục vụ; bàn chưa mở thì Tablet nhắc khách ra quầy Lễ tân.
     */
    public static String buildTabletSessionInfo(TableSession s) {
        if (s == null) return "SESSION_INFO||0||0";
        return "SESSION_INFO|" + NetworkPacket.enc(s.getCustomerName()) + "|" + s.getGuestCount() + "|" + NetworkPacket.enc(s.getWaiterName()) + "|1";
    }

    private static String limit(String s) {
        return s.length() > ProtocolConstants.MAX_NOTE_LENGTH ? s.substring(0, ProtocolConstants.MAX_NOTE_LENGTH) : s;
    }
}
