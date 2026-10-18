package com.haidilao.pos.protocol;

/**
 * Các loại lệnh trong giao thức tầng ứng dụng POS Haidilao
 */
public enum CommandType {
    AUTH,                 // Xác thực thiết bị lúc bắt tay kết nối
    AUTH_ACK,             // Phản hồi xác thực thành công từ Server
    AUTH_FAIL,            // Phản hồi từ chối xác thực (sai mật khóa / vi phạm MAC)
    RING,                 // Tín hiệu chuông gọi phục vụ
    ORDER,                // Gói tin đặt món ăn mới
    ORDER_STATUS_UPDATE,  // Cập nhật trạng thái đơn (chế biến / lên món / hoàn tất)
    GET_MENU,             // Tablet xin danh sách thực đơn từ Server (Server trả MENU_BEGIN / MENU_ITEM / MENU_END)
    GET_HISTORY,          // Client xin lịch sử trò chuyện gần nhất (Server trả nhiều dòng HISTORY rồi HISTORY_END)
    CHAT_TO_RECEPTION,    // Tin nhắn từ Tablet bàn gửi Lễ tân
    CHAT_TO_TABLE,        // Tin nhắn phản hồi từ Lễ tân gửi về Tablet bàn
    TABLE_STATE,          // Thông báo trạng thái bàn ONLINE / OFFLINE
    PORT_SHUTDOWN,        // Lệnh ngắt kết nối và khóa cổng từ Admin (Err-Disable)
    SERVER_ACK,           // Xác nhận máy chủ đã nhận lệnh
    PING,                 // Kiểm tra kết nối sống (Keep-alive)
    PONG,                 // Phản hồi kết nối sống
    ERR_DISABLE           // Trạng thái cổng switch bị khóa do vi phạm bảo mật
}
