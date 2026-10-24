package com.haidilao.pos.client;

/**
 * Hộp thoại con (chi tiết bàn, thanh toán, đặt bàn...) đăng ký nhận các bản tin Server gửi về cửa sổ chính.
 * f là các trường của bản tin (f[0] = tên lệnh), raw là nguyên dòng.
 */
public interface MessageListener {
    void onServerMessage(String[] f, String raw);
}
