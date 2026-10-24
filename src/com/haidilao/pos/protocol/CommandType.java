package com.haidilao.pos.protocol;

/**
 * Các lệnh Client gửi lên Server (dòng đầu tiên của mỗi bản tin, trước dấu |).
 * Các bản tin Server gửi xuống (MENU_ITEM, NEW_ORDER, BILL...) được mô tả trong NetworkPacket.
 */
public enum CommandType {
    AUTH,                 // Xác thực thiết bị lúc bắt tay kết nối (Lễ tân, Bếp gửi kèm tài khoản nhân viên)
    AUTH_ACK,             // Phản hồi xác thực thành công từ Server
    AUTH_FAIL,            // Phản hồi từ chối xác thực (sai khóa, sai mật khẩu, thiết bị bị khóa)
    PING,                 // Heartbeat: client gửi mỗi 5 giây
    PONG,                 // Server trả lời heartbeat
    SERVER_ACK,           // Thông báo dạng chữ từ Server

    // Tablet tại bàn
    GET_MENU,             // Xin thực đơn + combo (Server trả MENU_BEGIN / MENU_ITEM / COMBO_ITEM / MENU_END)
    ORDER,                // Gọi món: danh sách món / combo kèm ghi chú
    RING,                 // Bấm chuông gọi nhân viên
    CHAT_TO_RECEPTION,    // Khách nhắn Lễ tân
    GET_MY_ORDERS,        // Các đơn của lượt phục vụ hiện tại (MY_ORDER ... MY_ORDERS_END)
    GET_BILL,             // Hóa đơn tạm tính + thông tin chuyển khoản (BILL_PREVIEW)
    PAY_REQUEST,          // Khách yêu cầu thanh toán: Tiền mặt / Chuyển khoản

    // Dùng chung
    GET_HISTORY,          // Lịch sử trò chuyện gần nhất (Server trả nhiều dòng HISTORY rồi HISTORY_END)

    // Quầy Lễ tân
    GET_TABLES,           // Sơ đồ bàn (TABLES_BEGIN / TABLE_INFO / WAITER / TABLES_END)
    GET_TABLE_DETAIL,     // Chi tiết một bàn: khách, đơn, chat (TABLE_SESSION / TABLE_ORDER / TABLE_CHAT / TABLE_DETAIL_END)
    SAVE_SESSION,         // Mở bàn / lưu thông tin khách, số khách, nhân viên phục vụ, ghi chú
    CHECKOUT,             // Thanh toán bàn (Server trả BILL)
    SET_TABLE_STATUS,     // Đổi trạng thái bàn: đã dọn (TRỐNG), đặt trước (ĐÃ ĐẶT)
    MOVE_TABLE,           // Chuyển khách sang bàn trống khác
    CANCEL_SESSION,       // Hủy bàn: khách về khi chưa gọi món (bàn đã có món thì phải thanh toán)
    GET_RESERVATIONS,     // Lượt đặt bàn của một ngày (RESERVATIONS_BEGIN / RESERVATION / RESERVATIONS_END)
    SAVE_RESERVATION,     // Thêm / sửa lượt đặt bàn
    RESERVATION_STATUS,   // Hủy / Không đến
    CHECK_IN,             // Khách đặt trước đã đến: mở bàn
    CHAT_TO_TABLE,        // Lễ tân trả lời một bàn
    ORDER_STATUS_UPDATE,  // Lễ tân báo đã phục vụ / hủy đơn
    TABLE_STATE,          // Server báo Tablet của bàn ONLINE / OFFLINE

    GET_CHAT,             // Lịch sử chat của MỘT bàn theo phạm vi: lượt khách / hôm nay / 7 ngày (Lễ tân, Phục vụ)
    GET_CONVERSATIONS,    // Danh sách cuộc trò chuyện của các bàn (Lễ tân)

    // Nhân viên phục vụ
    GET_MY_TABLES,        // Các bàn Lễ tân giao cho nhân viên này + đơn chưa mang ra

    // Màn hình Bếp
    GET_KITCHEN_ORDERS,   // Danh sách đơn cho bếp (KITCHEN_BEGIN / KITCHEN_ORDER / KITCHEN_END)
    KITCHEN_STATUS        // Bếp báo đang nấu / đã xong
}
