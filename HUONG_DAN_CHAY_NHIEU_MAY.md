# Hướng dẫn đóng gói và chạy Haidilao POS trên nhiều máy

## Tóm tắt: mỗi máy cần gì

| Máy | Chạy chức năng | Cần cài |
|---|---|---|
| **Máy chủ** (1 máy) | **[1] Máy chủ** (có thể chạy thêm [2] Lễ tân) | **Java 17+** · **Laragon** (MySQL) có database `haidilao_pos_db` · mở cổng 8888 |
| **Máy Lễ tân** | **[2] Lễ tân** | Chỉ cần **Java 17+** |
| **Máy Tablet** | **[3] Tablet** (mỗi máy chọn 1 bàn khác nhau) | Chỉ cần **Java 17+** |

Chỉ máy chủ có MySQL. Các máy khác lấy thực đơn, ảnh, lịch sử chat... **qua Server bằng socket**, không kết nối MySQL.

Nội dung thư mục project:
```
HaidilaoNetworkPOS/
├─ dist/                        ← BẢN CHẠY: HaidilaoNetworkPOS.jar + lib/ (driver MySQL)
├─ database/haidilao_pos_db.sql ← dữ liệu MySQL (thực đơn + ảnh, đơn, chat, thiết bị)
├─ ChayHaidilao.bat             ← nhấp đúp để chạy chương trình
├─ MoCongFirewall_8888.bat      ← chỉ chạy trên MÁY CHỦ (Run as administrator)
├─ XuatDatabase.bat             ← xuất lại database trước khi nén
├─ lib/                         ← driver MySQL dùng khi mở project bằng NetBeans
├─ src/, nbproject/, build.xml  ← mã nguồn (mở bằng NetBeans)
└─ HUONG_DAN_CHAY_NHIEU_MAY.md  ← file này
```

---

## Bước 1: Đóng gói trên máy của bạn

1. *(Nếu muốn ảnh nét)* Chạy Server → **THỰC ĐƠN** → chọn lại ảnh gốc cho từng món → **LƯU SỬA ĐỔI**.
2. NetBeans: chuột phải project → **Clean and Build**. Lệnh này tạo lại `dist/` với code mới nhất.
3. Bật Laragon → nhấp đúp **`XuatDatabase.bat`**. Lệnh này cập nhật `database/haidilao_pos_db.sql`.
   ⚠ Làm bước này **sau cùng**, để file `.sql` có đủ ảnh và dữ liệu mới nhất.
4. Nén **cả thư mục `HaidilaoNetworkPOS`** thành `.zip`. Có thể bỏ thư mục `build/` (NetBeans tự tạo lại) và `nbproject/private/` (cấu hình riêng của máy bạn) cho nhẹ.

> Máy Lễ tân hoặc Tablet thật ra chỉ cần thư mục **`dist/`** và file **`ChayHaidilao.bat`**. Nén cả project cũng không sao.

---

## Bước 2: Cài trên mọi máy: Java 17 trở lên

1. Mở **cmd** và gõ `java -version`.
   - Ra `version "17..."`, `"21..."`, `"22..."`... là được.
   - Báo *"java is not recognized"* thì cần cài Java: tải **JDK 21** (Oracle hoặc Eclipse Temurin), cài bằng cách Next liên tục rồi mở lại cmd và kiểm tra lại.
2. Giải nén file `.zip` vào một thư mục, ví dụ `D:\HaidilaoNetworkPOS`.

---

## Bước 3: Chuẩn bị máy chủ

> Nếu **máy của bạn** (đang có Laragon và database) làm máy chủ thì bỏ qua mục 3.1 và 3.2.

### 3.1 Cài Laragon
Cài Laragon (bản có MySQL 8) → mở Laragon → bấm **Start All**.
*(Dùng XAMPP cũng được. File `.sql` dùng bảng mã `utf8mb4_unicode_ci`, chạy được cả trên MySQL và MariaDB.)*

### 3.2 Nhập database (chọn 1 trong 3 cách)
- **HeidiSQL** (Laragon → nút **Database**): kết nối `root`, mật khẩu để trống → menu **File → Run SQL file...** → chọn `database\haidilao_pos_db.sql` → chạy.
- **phpMyAdmin**: tab **Import** → chọn file `.sql` → **Go**.
- **Dòng lệnh** (cmd mở trong thư mục project):
  ```
  D:\laragon\bin\mysql\mysql-8.0.30-winx64\bin\mysql.exe -u root --default-character-set=utf8mb4 < database\haidilao_pos_db.sql
  ```
  (sửa đường dẫn `mysql.exe` cho đúng với máy đó)

File `.sql` **tự tạo database** `haidilao_pos_db`, không cần tạo trước.

⚠ Code kết nối MySQL bằng `root` với **mật khẩu rỗng** (mặc định của Laragon). Nếu MySQL trên máy chủ có đặt mật khẩu cho `root`, phải sửa `PASS` trong `src/com/haidilao/pos/database/DatabaseManager.java` rồi Clean and Build lại.

### 3.3 Mở cổng 8888
1. Chuột phải **`MoCongFirewall_8888.bat`** → **Run as administrator**.
2. Settings → Network & Internet → Wi-Fi → (mạng đang dùng) → chọn **Private network**.
3. Lần đầu chạy Server, nếu Windows hỏi *"Allow access"* thì tick **Private** → **Allow**.

---

## Bước 4: Nối mạng

- **Tất cả máy kết nối cùng một mạng.** Nên dùng **hotspot điện thoại** hoặc router riêng.
- **Không dùng Wi-Fi trường hay quán cà phê.** Các mạng này thường chặn máy trong mạng kết nối với nhau (client isolation).

---

## Bước 5: Chạy (đúng thứ tự)

1. **Máy chủ:** nhấp đúp `ChayHaidilao.bat` → **[1] Máy chủ** → **KHỞI ĐỘNG SERVER**.
   - Nhãn MySQL phải **xanh** "KẾT NỐI OK". Nếu đỏ, kiểm tra Laragon đã Start chưa.
   - Ô nhật ký in ra các IP, ví dụ:
     ```
     => ... nhập IP: 172.31.160.1   (card mạng: Hyper-V Virtual Ethernet Adapter)  ← card ẢO, bỏ qua
     => ... nhập IP: 192.168.43.25  (card mạng: Intel(R) Wireless-AC 8265)         ← card Wi-Fi: DÙNG IP NÀY
     ```
2. **Kiểm tra kết nối** từ một máy khác (mở PowerShell):
   ```
   Test-NetConnection 192.168.43.25 -Port 8888
   ```
   Thấy `TcpTestSucceeded : True` là thông. Ra `False` thì xem lại Bước 3.3 và Bước 4. (Đừng dùng `ping` để kiểm tra, Windows thường chặn ping.)
3. **Máy Lễ tân:** `ChayHaidilao.bat` → gõ IP máy chủ vào ô **Địa chỉ IP Máy Chủ** → **[2] Lễ tân**.
4. **Máy Tablet:** `ChayHaidilao.bat` → gõ IP → **[3] Tablet** → chọn bàn. **Mỗi máy một bàn khác nhau.**
5. Bên Lễ tân thấy thẻ bàn chuyển **xanh**. Tablet hiện **"Lễ tân: trực tuyến"** và thực đơn có ảnh.

> **Chạy thử trên 1 máy:** `ChayHaidilao.bat` → **[4] Chạy toàn bộ demo**. Chỉ dùng để tập dượt: đóng 1 cửa sổ là tắt hết, và khóa cổng một Tablet cũng tắt luôn Server.

---

## Bước 6: Sự cố thường gặp

| Hiện tượng | Cách xử lý |
|---|---|
| Nhấp đúp `ChayHaidilao.bat` báo chưa cài Java | Làm Bước 2 |
| `UnsupportedClassVersionError` | Java quá cũ, cài Java 17 trở lên |
| Client báo **"Không kết nối được tới Server ... (connect timed out)"** | Sai IP (dùng nhầm card ảo), khác mạng, hoặc Wi-Fi chặn → đọc lại IP card Wi-Fi, dùng hotspot |
| Báo **"(Connection refused)"** | Máy chủ chưa bấm KHỞI ĐỘNG SERVER |
| Nhãn MySQL đỏ trên máy chủ | Laragon chưa Start, chưa import `.sql`, hoặc MySQL có mật khẩu (xem 3.2) |
| Tablet chỉ hiện thực đơn mặc định, không có ảnh | Database trên máy chủ trống, cần import lại `.sql` |
| Hộp thoại **"Không thể kết nối hệ thống: ... đang bị khóa"** | Thiết bị đang bị khóa cổng: Server → **MỞ CỔNG** → mở lại app |
| Hai bàn "giành nhau", tin nhắn tới nhầm máy | Hai máy chọn **trùng tên bàn** |
| `BindException` khi khởi động Server | Đã có một Server đang chạy, tắt bản cũ |

---

## (Tuỳ chọn) Mở project bằng NetBeans trên máy khác

**File → Open Project** → chọn thư mục `HaidilaoNetworkPOS`. Driver MySQL đã nằm sẵn trong `lib/` nên NetBeans không báo thiếu thư viện. Nếu NetBeans hỏi chọn JDK, chọn JDK 17 trở lên.
