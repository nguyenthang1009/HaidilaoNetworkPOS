# Haidilao Network POS: Gọi món & Nhắn tin trong mạng LAN

Đồ án môn **Lập trình mạng máy tính**: ứng dụng gọi món, gọi phục vụ và nhắn tin tức thời cho nhà hàng lẩu.
Các máy kết nối với nhau qua **TCP Socket** (cổng **8888**) theo mô hình **Client – Server**. Dữ liệu được lưu trong **MySQL**.

```
        Máy TABLET (bàn khách)                         MÁY CHỦ (Server)
   ┌──────────────────────────┐                ┌───────────────────────────┐
   │ Xem thực đơn có ảnh      │                │ Quản lý thiết bị kết nối  │
   │ Đặt món, bấm chuông      │◄── Wi-Fi ─────►│ Quản lý thực đơn + ảnh    │◄──► MySQL
   │ Chat với Lễ tân          │    TCP 8888    │ Khóa / mở cổng thiết bị   │
   └──────────────────────────┘                └───────────────────────────┘
        Máy LỄ TÂN (thu ngân)                              ▲
   ┌──────────────────────────┐                            │
   │ Nhận đơn, xác nhận đơn   │◄───────────────────────────┘
   │ Thấy bàn nào đang gọi    │
   │ Trả lời tin nhắn khách   │
   └──────────────────────────┘
```

| Vai trò | Chạy chức năng | Máy cần cài |
|---|---|---|
| **Máy chủ** (chỉ 1 máy) | nút `1. [MÁY CHỦ]` | Java 17+ **và** Laragon (MySQL) |
| **Lễ tân** | nút `2. [LỄ TÂN]` | Chỉ cần Java 17+ |
| **Tablet** (mỗi bàn 1 máy) | nút `3. [TABLET]` | Chỉ cần Java 17+ |

> Chỉ **máy chủ** cần MySQL. Lễ tân và Tablet nhận mọi dữ liệu (thực đơn, ảnh, lịch sử chat...) **qua Server**.

---

## Bước 0: Tải project về

Trên trang GitHub này, bấm nút xanh **`<> Code`** → **Download ZIP** → giải nén ra một thư mục, ví dụ `D:\HaidilaoNetworkPOS`.

*(Ai biết dùng git thì có thể `git clone <link repo này>`.)*

---

## Bước 1: Cài Java (tất cả các máy)

1. Bấm phím Windows, gõ `cmd`, Enter. Trong cửa sổ cmd gõ:
   ```
   java -version
   ```
2. Thấy dòng `version "17..."` hoặc số lớn hơn (`21`, `22`...) thì **đã có Java**, sang bước tiếp.
3. Nếu báo *"java is not recognized..."*:
   - Tải **JDK 21** (tìm "Eclipse Temurin JDK 21" hoặc "Oracle JDK 21"), chọn bản **Windows x64 Installer (.msi)**.
   - Cài bằng cách bấm Next đến hết.
   - Đóng cmd, mở lại, gõ lại `java -version` để kiểm tra.

---

## Bước 2: Chuẩn bị MÁY CHỦ (chỉ làm trên 1 máy)

### 2.1 Cài và bật MySQL bằng Laragon
1. Tải và cài **Laragon** (bản Full).
2. Mở Laragon → bấm **Start All**. Thấy MySQL báo chạy là được.

### 2.2 Nhập dữ liệu (database)
Trong Laragon bấm nút **Database** để mở HeidiSQL:
1. Bấm **Open** để kết nối (User `root`, Password **để trống**).
2. Menu **File → Run SQL file...** → chọn file **`database\haidilao_pos_db.sql`** trong thư mục project.
3. Chạy xong, bấm **F5**. Bên trái phải có database **`haidilao_pos_db`**.

> File `.sql` tự tạo database, không cần tạo trước. Dùng XAMPP thay Laragon cũng được: vào phpMyAdmin → tab **Import** → chọn file `.sql` → **Go**.

### 2.3 Mở cổng 8888 cho máy khác vào được
1. Trong thư mục project, **chuột phải** file **`MoCongFirewall_8888.bat`** → **Run as administrator** → bấm **Yes**.
2. Vào **Settings → Network & Internet → Wi-Fi** → bấm vào mạng đang dùng → chọn **Private network**.

---

## Bước 3: Cho tất cả các máy vào CÙNG một mạng

- Nên dùng **điểm phát Wi-Fi (hotspot) từ điện thoại**: 1 điện thoại phát, mọi laptop cùng kết nối vào.
- **Không dùng Wi-Fi của trường hay quán cà phê.** Các mạng này thường chặn các máy kết nối với nhau, nên app sẽ báo "Không kết nối được tới Server".

---

## Bước 4: Chạy chương trình (làm ĐÚNG thứ tự)

Trên mỗi máy, **nhấp đúp file `ChayHaidilao.bat`** sẽ hiện màn hình chọn chức năng.

**① Máy chủ chạy trước**
1. Bấm nút **1. [MÁY CHỦ]** → bấm **KHỞI ĐỘNG SERVER (8888)**.
2. Ô **MYSQL** phải chuyển **xanh** ("KẾT NỐI OK"). Nếu đỏ thì xem lại Bước 2.
3. Ô nhật ký bên phải in ra địa chỉ IP, ví dụ:
   ```
   => ... nhập IP: 172.31.160.1    (card mạng: Hyper-V Virtual Ethernet Adapter)   ← card ẢO, BỎ QUA
   => ... nhập IP: 192.168.43.25   (card mạng: Intel(R) Wireless-AC 8265)          ← card WI-FI, DÙNG IP NÀY
   ```
   **Ghi lại IP của dòng có chữ Wi-Fi / Wireless** và gửi cho các bạn còn lại.

**② Máy Lễ tân**
Gõ IP máy chủ vào ô **"Địa chỉ IP Máy Chủ POS"** → bấm nút **2. [LỄ TÂN]**.

**③ Các máy Tablet**
Gõ IP máy chủ → bấm nút **3. [TABLET]** → chọn bàn.
⚠ **Mỗi máy chọn một bàn KHÁC nhau** (Tablet_Ban1, Tablet_Ban2, Tablet_Ban3...).

Kết nối thành công khi:
- Bên **Lễ tân**, thẻ bàn chuyển màu **xanh lá**.
- Bên **Tablet**, thanh đỏ trên cùng ghi **"Sẵn sàng phục vụ · Lễ tân: trực tuyến"** và thực đơn có hình món ăn.

---

## Bước 5: Thử các chức năng

| Làm gì | Ở máy | Kết quả mong đợi |
|---|---|---|
| Bấm **+ Thêm** vài món → **GỬI ĐƠN XUỐNG BẾP** | Tablet | Lễ tân nhận đơn mới, thẻ bàn chuyển **cam** |
| Chọn đơn → **XÁC NHẬN CHUYỂN BẾP** | Lễ tân | Tablet nhận thông báo trạng thái món |
| Bấm **[CHUÔNG] Gọi Nhân Viên** | Tablet | Thẻ bàn bên Lễ tân chuyển **đỏ** |
| Gõ tin nhắn → **Gửi** | Tablet | Lễ tân nhận được; Tablet hiện "→ Đã chuyển tới Lễ tân" |
| Chọn bàn trong ô bên dưới → gõ trả lời → **Gửi Phản Hồi** | Lễ tân | Đúng bàn đó nhận được |
| **THỰC ĐƠN** → chọn món → **CHỌN ẢNH** → **LƯU SỬA ĐỔI** | Máy chủ | Thực đơn trên các Tablet tự cập nhật ảnh |
| Chọn 1 Tablet trong bảng → **KHÓA CỔNG** | Máy chủ | Tablet đó bị ngắt và không vào lại được, đến khi bấm **MỞ CỔNG** |
| Tắt Wi-Fi một Tablet, chờ khoảng 15 giây | Tablet | Lễ tân thấy bàn đó chuyển **xám (OFFLINE)** |
| Tắt rồi mở lại app Tablet | Tablet | Lịch sử tin nhắn cũ hiện lại |

> **Chỉ có 1 máy?** Bấm nút **4. [CHẠY THỬ]** để mở Máy chủ + Lễ tân + 2 Tablet trên cùng một máy. Lưu ý: đóng 1 cửa sổ là tắt hết, và đừng thử **khóa cổng** ở chế độ này (sẽ tắt luôn Server).

---

## Gặp lỗi thì làm gì?

| Hiện tượng | Nguyên nhân thường gặp | Cách sửa |
|---|---|---|
| Nhấp `ChayHaidilao.bat` báo **chưa cài Java** | Chưa có Java | Làm Bước 1 |
| Báo lỗi `UnsupportedClassVersionError` | Java quá cũ | Cài Java 17 trở lên |
| **"Không kết nối được tới Server ... (connect timed out)"** | Nhập sai IP (nhầm card ảo), khác mạng, hoặc Wi-Fi chặn | Lấy lại IP của card **Wi-Fi** trên máy chủ; dùng hotspot điện thoại |
| **"... (Connection refused)"** | Máy chủ chưa bấm KHỞI ĐỘNG SERVER | Bấm Khởi động trên máy chủ |
| Ô **MYSQL đỏ** trên máy chủ | Laragon chưa Start, hoặc chưa nhập database | Start All trong Laragon; làm lại Bước 2.2 |
| Tablet chỉ có thực đơn mặc định, **không có ảnh** | Database trên máy chủ trống | Nhập lại file `.sql` (Bước 2.2) |
| Hiện **"Không thể kết nối hệ thống: ... đang bị khóa"** | Máy đó đang bị khóa cổng | Máy chủ bấm **MỞ CỔNG** → mở lại app |
| Hai bàn bị lẫn tin nhắn | Hai máy chọn **trùng tên bàn** | Mỗi máy chọn bàn khác nhau |

**Kiểm tra mạng nhanh:** trên máy Lễ tân hoặc Tablet, mở **PowerShell** và gõ (thay IP của máy chủ):
```
Test-NetConnection 192.168.43.25 -Port 8888
```
Ra `TcpTestSucceeded : True` là mạng thông. Ra `False` thì xem lại Bước 2.3 và Bước 3.

---

## Dành cho ai muốn mở và sửa code

- Mở bằng **NetBeans** (bản 17 trở lên): **File → Open Project** → chọn thư mục project. Driver MySQL đã có sẵn trong `lib/`.
- Sửa xong: chuột phải project → **Clean and Build** để tạo lại `dist/HaidilaoNetworkPOS.jar`.
- MySQL mặc định: `root`, mật khẩu rỗng, database `haidilao_pos_db`. Nếu máy chủ có mật khẩu MySQL, sửa `PASS` trong `src/com/haidilao/pos/database/DatabaseManager.java`.

### Cấu trúc thư mục
```
├─ dist/                        File chạy: HaidilaoNetworkPOS.jar + lib/ (driver MySQL)
├─ database/haidilao_pos_db.sql Dữ liệu mẫu MySQL (thực đơn + ảnh, đơn, chat)
├─ ChayHaidilao.bat             Nhấp đúp để chạy
├─ MoCongFirewall_8888.bat      Mở cổng 8888 (chỉ máy chủ, Run as administrator)
├─ XuatDatabase.bat             Xuất lại database ra file .sql
├─ HUONG_DAN_CHAY_NHIEU_MAY.md  Hướng dẫn chi tiết hơn
├─ lib/                         Driver MySQL cho NetBeans
└─ src/com/haidilao/pos/
   ├─ server/    PosServer (lắng nghe cổng 8888), ClientSession (1 luồng cho mỗi máy kết nối)
   ├─ client/    PosClient (kết nối, tự kết nối lại, heartbeat)
   ├─ protocol/  Giao thức: định dạng gói tin LỆNH|dữ liệu|...
   ├─ security/  Mã hóa AES, xác thực, phân quyền
   ├─ database/  Truy vấn MySQL
   ├─ admin/     Hộp thoại quản lý thực đơn
   └─ *.java     Giao diện: ServerAdminFrame, ReceptionFrame, TabletClientFrame
```
