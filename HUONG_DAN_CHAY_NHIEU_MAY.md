# Hướng dẫn chạy trên nhiều máy

## Mỗi máy cần gì

| Máy | Chọn trong `ChayHaidilao.bat` | Cần cài |
|---|---|---|
| **Máy chủ** (1 máy) | **MÁY CHỦ** | Java 17+ · Laragon (MySQL) · chạy `MoCongFirewall_8888.bat` |
| **Quầy Lễ tân** | **QUẦY LỄ TÂN** | Chỉ cần Java 17+ |
| **Màn hình Bếp** | **MÀN HÌNH BẾP** | Chỉ cần Java 17+ |
| **Nhân viên phục vụ** | **NHÂN VIÊN PHỤC VỤ** | Chỉ cần Java 17+ |
| **Tablet** (mỗi bàn 1 máy) | **TABLET TẠI BÀN** | Chỉ cần Java 17+ |

Chỉ máy chủ kết nối MySQL. Các máy khác nhận mọi dữ liệu (thực đơn, ảnh, sơ đồ bàn, đơn, tin nhắn) **qua socket**.
**Không cần nhập IP:** máy khách gửi gói hỏi **UDP broadcast** tới cổng 8889, máy chủ trả lời, máy khách lấy IP từ gói trả lời
rồi kết nối **TCP 8888**. Máy chủ đổi IP (DHCP cấp lại) thì các máy vẫn tự tìm lại được.

Máy Lễ tân / Bếp / Tablet thật ra chỉ cần thư mục **`dist/`** và file **`ChayHaidilao.bat`**.

---

## Bước 1: Đóng gói (trên máy của bạn)

1. NetBeans: chuột phải project → **Clean and Build** (tạo lại `dist/` bằng code mới nhất).
2. Nén **cả thư mục project** thành `.zip` (có thể bỏ `build/` và `nbproject/private/`), hoặc để các bạn tải từ GitHub.

> `XuatDatabase.bat` xuất **toàn bộ** database đang chạy, gồm cả đơn, tin nhắn chạy thử và địa chỉ MAC các máy.
> Dùng file này để **sao lưu**; trước khi đẩy lên GitHub nên dùng bản dữ liệu mẫu sạch.

## Bước 2: Máy chủ

1. Laragon → **Start All**.
2. *(Không bắt buộc)* Nhập `database\haidilao_pos_db.sql` (HeidiSQL → File → Run SQL file) để có sẵn ảnh món.
   Không nhập thì lần đầu bật máy chủ, chương trình tự tạo database và dữ liệu mẫu.
3. **Chuột phải `MoCongFirewall_8888.bat` → Run as administrator** (mở TCP 8888 + UDP 8889).
4. Wi-Fi đặt ở chế độ **Private network**. Lần đầu chạy, Windows hỏi *"Allow access"* thì chọn **Private → Allow**.

⚠ Code dùng MySQL `root` **mật khẩu rỗng** (mặc định Laragon). Nếu MySQL có mật khẩu:
`java -Dpos.db.pass=matkhau -jar dist\HaidilaoNetworkPOS.jar`

## Bước 3: Nối mạng

Tất cả máy kết nối **cùng một mạng**: hotspot điện thoại hoặc router riêng.
**Không dùng Wi-Fi trường / quán cà phê** (chặn các máy trong mạng nói chuyện với nhau và thường chặn cả broadcast).

## Bước 4: Chạy (đúng thứ tự)

1. **Máy chủ:** `ChayHaidilao.bat` → **MÁY CHỦ** → đăng nhập `admin` / `admin123`.
   Server tự khởi động; dòng dưới cùng hiện địa chỉ máy chủ (chỉ để tham khảo).
2. **Lễ tân:** **QUẦY LỄ TÂN** → đăng nhập `thungan` / `123456`. Hộp đăng nhập hiện "Đang tìm máy chủ..." rồi vào.
3. **Bếp:** **MÀN HÌNH BẾP** → đăng nhập `bep` / `123456`.
3b. **Phục vụ:** **NHÂN VIÊN PHỤC VỤ** → đăng nhập `phucvu1` (hoặc `phucvu2`) / `123456`. Bàn nào Lễ tân giao thì hiện ở đây.
4. **Tablet:** **TABLET TẠI BÀN** → chương trình tìm máy chủ và hiện danh sách bàn → chọn bàn → **Bắt đầu**.
   **Mỗi máy một bàn khác nhau.** Lần sau mở lên tự vào đúng bàn (lưu trong `%USERPROFILE%\.lan-table-ordering`).
5. Kết nối thành công: Lễ tân thấy thẻ bàn hết dòng "Tablet offline"; Tablet hiện **"Đã kết nối · Lễ tân trực tuyến"**.

> Khách vào bàn phải **ra quầy Lễ tân để được xếp bàn** thì Tablet mới gọi món được (Tablet hiện lời nhắc).

## Bước 5: Sự cố thường gặp

| Hiện tượng | Cách xử lý |
|---|---|
| Cứ báo **"Đang tìm máy chủ..."** | Máy chủ chưa mở; khác mạng; chưa chạy `MoCongFirewall_8888.bat`; Wi-Fi chặn broadcast → dùng hotspot, hoặc chỉ định IP: `java -Dpos.server=IP_MÁY_CHỦ -jar dist\HaidilaoNetworkPOS.jar` |
| Tìm thấy máy chủ nhưng **không kết nối được** | Cổng TCP 8888 bị chặn → chạy lại `MoCongFirewall_8888.bat`, kiểm tra `Test-NetConnection IP_MÁY_CHỦ -Port 8888` |
| Đăng nhập bị từ chối | Sai tài khoản / mật khẩu, hoặc vai trò không được dùng máy đó (vd. Thu ngân không vào Bếp) |
| **"Thiết bị đã bị quản trị viên khóa"** | Máy chủ → **Mở khóa thiết bị** → mở lại app |
| Hai Tablet giành nhau một bàn | Hai máy chọn trùng bàn → mở **TABLET TẠI BÀN** từ trình khởi chạy để chọn lại |
| Máy chủ báo **không mở được cổng 8888** | Đang có một máy chủ khác chạy, tắt bản cũ |
