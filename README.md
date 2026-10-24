# LAN Table Ordering & Service Bell

Đồ án môn **Lập trình mạng máy tính**: ứng dụng gọi món tại bàn, gọi phục vụ, nhắn tin, đặt bàn và thanh toán cho nhà hàng lẩu.
Các máy nói chuyện với nhau theo mô hình **Client – Server** qua **TCP Socket (cổng 8888)**, tự tìm máy chủ trong mạng Wi-Fi bằng
**UDP broadcast (cổng 8889)**, dữ liệu trên đường truyền được **mã hóa AES-128**. Chỉ máy chủ lưu dữ liệu vào **MySQL**.

```
   TABLET tại bàn (khách)           MÁY CHỦ (quản lý)                 QUẦY LỄ TÂN (thu ngân)
 ┌───────────────────────┐      ┌────────────────────────┐        ┌─────────────────────────┐
 │ Thực đơn + combo có ảnh│      │ Server TCP 8888        │        │ Sơ đồ bàn, ghế trống    │
 │ Giỏ hàng, ghi chú bếp  │◄────►│ Tự trả lời UDP 8889    │◄──────►│ Xếp bàn, đặt bàn trước  │
 │ Đơn của bàn (STT)      │ Wi-Fi│ Thực đơn, combo, bàn,  │  Wi-Fi │ Đơn hàng, tin nhắn      │
 │ Nhắn Lễ tân, gọi PV    │      │ nhân viên, thống kê    │        │ Thanh toán, hủy / chuyển│
 │ Yêu cầu thanh toán     │      │ Khóa thiết bị          │        └─────────────────────────┘
 └───────────────────────┘      └───────────┬────────────┘        ┌─────────────────────────┐
                                             │ MySQL              │ MÀN HÌNH BẾP            │
                                             ▼                    │ Phiếu đơn, số phút chờ  │◄──► Máy chủ
                                        (Laragon)                 │ Bắt đầu nấu / Xong      │
                                                                  └─────────────────────────┘
```

| Vai trò | Ai dùng | Đăng nhập | Máy cần cài |
|---|---|---|---|
| **Máy chủ** (chỉ 1 máy) | Quản lý | Tài khoản Quản lý | Java 17+ **và** Laragon (MySQL) |
| **Quầy Lễ tân** | Thu ngân / Lễ tân | Tài khoản Quản lý hoặc Thu ngân | Chỉ cần Java 17+ |
| **Màn hình Bếp** | Bếp | Tài khoản Quản lý hoặc Bếp | Chỉ cần Java 17+ |
| **Nhân viên phục vụ** | Phục vụ (mỗi người 1 máy / điện thoại-laptop) | Tài khoản Phục vụ hoặc Quản lý | Chỉ cần Java 17+ |
| **Tablet** (mỗi bàn 1 máy) | Khách | Không (Tablet gắn với 1 bàn) | Chỉ cần Java 17+ |

**Không cần nhập IP máy chủ.** Các máy trong cùng mạng Wi-Fi tự tìm thấy máy chủ.

### Tài khoản mẫu

| Tài khoản | Mật khẩu | Vai trò | Dùng được |
|---|---|---|---|
| `admin` | `admin123` | Quản lý | Máy chủ, Lễ tân, Bếp |
| `thungan` | `123456` | Thu ngân | Lễ tân |
| `bep` | `123456` | Bếp | Màn hình Bếp |
| `phucvu1`, `phucvu2` | `123456` | Phục vụ | Máy Nhân viên phục vụ (nhận bàn Lễ tân giao) |

Mật khẩu được lưu dạng băm **SHA-256 + salt**. Đổi mật khẩu trong **Máy chủ → Nhân viên & tài khoản**.

---

## Bước 0: Tải project về

Trên GitHub bấm nút xanh **`<> Code`** → **Download ZIP** → giải nén, ví dụ vào `D:\HaidilaoNetworkPOS`.
*(Hoặc `git clone https://github.com/nguyenthang1009/HaidilaoNetworkPOS.git`)*

## Bước 1: Cài Java 17 trở lên (tất cả các máy)

Mở **cmd**, gõ `java -version`. Thấy `17`, `21`, `22`... là được. Báo *"java is not recognized"* thì cài **JDK 21**
(Eclipse Temurin hoặc Oracle, bản Windows x64 `.msi`), rồi mở lại cmd kiểm tra.

## Bước 2: Chuẩn bị MÁY CHỦ (chỉ 1 máy)

1. Cài **Laragon** → mở lên → **Start All**.
2. *(Không bắt buộc)* Nhập dữ liệu mẫu có sẵn ảnh món: Laragon → **Database** (HeidiSQL) → kết nối `root`, mật khẩu trống →
   **File → Run SQL file...** → chọn `database\haidilao_pos_db.sql`.
   Nếu bỏ qua bước này, lần đầu bật máy chủ chương trình **tự tạo database**, bàn, tài khoản, thực đơn và combo mẫu (chưa có ảnh).
3. **Chuột phải `MoCongFirewall_8888.bat` → Run as administrator.** File này mở **TCP 8888** và **UDP 8889**.
4. Settings → Network & Internet → Wi-Fi → mạng đang dùng → chọn **Private network**.

> Database của bản cũ vẫn dùng được: khi bật máy chủ, chương trình **tự nâng cấp** (thêm bảng, thêm cột, chuyển dữ liệu cũ).
> Nên chạy `XuatDatabase.bat` để sao lưu trước.

## Bước 3: Cho tất cả các máy vào CÙNG một mạng

Nên dùng **hotspot điện thoại** hoặc router riêng. **Không dùng Wi-Fi trường / quán cà phê** (thường chặn các máy kết nối với nhau).

## Bước 4: Chạy chương trình

Trên mỗi máy, nhấp đúp **`ChayHaidilao.bat`** → chọn vai trò của máy:

1. **Máy chủ** (bật trước): đăng nhập `admin` / `admin123`. Server tự khởi động, ô **Database** phải xanh.
2. **Quầy Lễ tân**: đăng nhập `thungan` / `123456`. Chương trình tự tìm máy chủ.
3. **Màn hình Bếp**: đăng nhập `bep` / `123456`.
4. **Nhân viên phục vụ**: đăng nhập `phucvu1` / `123456` (máy khác dùng `phucvu2`).
5. **Tablet tại bàn**: chọn bàn từ danh sách máy chủ gửi về (mỗi máy một bàn khác nhau). Lần sau mở lên tự vào đúng bàn.

> **Chỉ có 1 máy?** Bấm **"Chạy thử tất cả trên máy này"** để mở máy chủ + Lễ tân + Bếp + Tablet bàn 1 cùng lúc.

---

## Bước 5: Quy trình phục vụ để thử

| Bước | Ở máy | Làm gì | Kết quả |
|---|---|---|---|
| 1 | Lễ tân | **+ Xếp bàn cho khách** → nhập số khách, **tên khách**, chọn **nhân viên phục vụ** (thấy mỗi người đang phụ trách mấy bàn) → **Mở bàn** | Bàn chuyển *Đang phục vụ*, Tablet hiện lời chào và tên phục vụ, máy Phục vụ thấy bàn mới được giao |
| 2 | Tablet | Chọn món / combo, ghi chú từng món, ghi chú chung → **Gửi đơn xuống bếp** | Đơn có **STT trong ngày** (#001...), Lễ tân và Bếp nhận ngay |
| 3 | Bếp | **Bắt đầu nấu** → **Xong** | Tablet thấy trạng thái món; máy Phục vụ của bàn được báo "món xong - mang ra" |
| 4 | Phục vụ (hoặc Lễ tân) | Chọn món trong "Món cần mang ra" → **Đã mang món ra** | Đơn chuyển *Đã phục vụ* |
| 5 | Tablet | **Gọi phục vụ** / tab **Nhắn nhân viên** | Tin và chuông tới **nhân viên phục vụ của bàn**; Lễ tân cũng thấy để điều phối |
| 5b | Phục vụ / Lễ tân | Bấm vào bàn → trả lời (có câu trả lời nhanh) | Tablet hiện tin kèm tên người trả lời; Lễ tân có tab **Tin nhắn** chia riêng từng bàn, lọc theo lượt khách / hôm nay / 7 ngày |
| 6 | Tablet | **Thanh toán** → xem hóa đơn tạm tính → chọn *Tiền mặt* / *Chuyển khoản* → **Gửi yêu cầu** | Lễ tân nhận yêu cầu thanh toán |
| 7 | Lễ tân | Bấm vào bàn → **Thanh toán** → giảm giá, tiền khách đưa → **Xác nhận** | In hóa đơn, bàn chuyển *Cần dọn*, Tablet hiện lời cảm ơn |
| 8 | Lễ tân | **Đã dọn bàn xong** | Bàn về *Trống* |
| 9 | Lễ tân | Tab **Đặt bàn** → **+ Đặt bàn mới** → khi khách đến bấm **Khách đã đến - nhận bàn** | Thẻ bàn hiện *Đã đặt* trước giờ hẹn 60 phút |
| 10 | Máy chủ | **Thống kê doanh thu** | Doanh thu, biểu đồ theo ngày, món bán chạy, theo bàn, theo nhân viên |
| 11 | Máy chủ | **Lịch sử tin nhắn** → lọc ngày, bàn, từ khóa / tên nhân viên | Xem lại hội thoại khi khách thắc mắc / khiếu nại, xuất CSV |

### Các quy tắc nghiệp vụ hệ thống tự kiểm tra

- Khách **phải ra quầy Lễ tân** để được xếp bàn (hoặc nhận bàn đặt trước) thì Tablet mới gọi món được.
- **Tên khách bắt buộc**; **số khách không được vượt số ghế** của bàn (khi mở bàn, sửa thông tin, nhận bàn, chuyển bàn).
- Bàn **đã gọi món thì phải thanh toán** mới trả bàn được. Khách về khi chưa gọi món: Lễ tân dùng **Hủy bàn** (giữ tiền cọc nếu có).
- Không thanh toán khi còn món **chưa mang ra**; không mở bàn hay chuyển khách sang bàn **chưa dọn**.
- Đặt bàn: không trùng giờ cùng bàn (giữ bàn 2 giờ), đủ ghế, không đặt vào giờ đã qua; không đánh "Không đến" trước giờ hẹn.
- **Máy chủ tự tính tiền** theo giá trong database, không tin giá Tablet gửi lên.
- Nhân viên phục vụ chỉ xem / nhắn / đánh dấu "đã mang ra" cho **các bàn Lễ tân giao cho mình**; không hủy đơn, không thanh toán.

Danh sách đầy đủ 20 tình huống ngoại lệ có trong tài liệu phân tích nghiệp vụ của nhóm.

---

## Gặp lỗi thì làm gì?

| Hiện tượng | Cách sửa |
|---|---|
| `ChayHaidilao.bat` báo chưa cài Java / `UnsupportedClassVersionError` | Cài Java 17 trở lên (Bước 1) |
| Lễ tân / Bếp / Tablet cứ báo **"Đang tìm máy chủ..."** | Máy chủ chưa mở, khác mạng Wi-Fi, chưa chạy `MoCongFirewall_8888.bat`, hoặc Wi-Fi chặn → dùng hotspot điện thoại |
| Máy chủ báo **chưa kết nối được MySQL** | Laragon chưa Start All |
| Đăng nhập báo **sai tài khoản** | Xem bảng tài khoản mẫu; sai 5 lần trong 1 phút sẽ bị tạm chặn 1 phút |
| Tablet báo **"Bàn chưa được mở"** | Đúng quy trình: khách ra quầy, Lễ tân **Xếp bàn** cho bàn đó |
| **"Thiết bị đã bị quản trị viên khóa"** | Máy chủ → **Mở khóa thiết bị** → mở lại app trên máy đó |
| Máy chủ báo **không mở được cổng 8888** | Đã có một máy chủ đang chạy, tắt bản cũ |

**Muốn chỉ định IP máy chủ bằng tay** (mạng chặn broadcast): chạy `java -Dpos.server=192.168.1.10 -jar dist\HaidilaoNetworkPOS.jar`.

---

## Dành cho ai mở và sửa code

- Mở bằng **NetBeans 17+**: **File → Open Project**. Driver MySQL có sẵn trong `lib/`.
- Các form (`*.form`) mở và sửa được bằng **GUI Builder (Design)** của NetBeans.
- Sửa xong: chuột phải project → **Clean and Build** để tạo lại `dist/HaidilaoNetworkPOS.jar`.
- MySQL mặc định `root`, mật khẩu rỗng, database `haidilao_pos_db`. Có thể đổi khi chạy:
  `java -Dpos.db.pass=matkhau -Dpos.db.name=ten_db -jar dist\HaidilaoNetworkPOS.jar`.
- Mở thẳng một vai trò: `java -jar dist\HaidilaoNetworkPOS.jar server | reception | kitchen | waiter | tablet [Tablet_Ban1]`.

### Cấu trúc thư mục
```
├─ dist/                         File chạy: HaidilaoNetworkPOS.jar + lib/ (driver MySQL)
├─ database/haidilao_pos_db.sql  Dữ liệu mẫu: thực đơn + ảnh, combo, bàn, tài khoản mẫu (không có đơn / tin nhắn)
├─ ChayHaidilao.bat              Nhấp đúp để chạy
├─ MoCongFirewall_8888.bat       Mở TCP 8888 + UDP 8889 (chỉ máy chủ, Run as administrator)
├─ XuatDatabase.bat              Xuất database ra file .sql (sao lưu)
└─ src/com/haidilao/pos/
   ├─ server/    PosServer (TCP 8888), ClientSession (1 luồng / máy), DiscoveryServer (UDP 8889), RestaurantService (nghiệp vụ)
   ├─ client/    PosClient (kết nối, tự kết nối lại, heartbeat), ServerLocator (tìm máy chủ bằng broadcast)
   ├─ protocol/  Giao thức: LỆNH|trường|..., chữ tự do được mã hóa URL
   ├─ security/  Mã hóa AES, băm mật khẩu, xác thực, phân quyền lệnh theo vai trò (RBAC)
   ├─ database/  DAO từng bảng + SchemaMigrator (tự tạo / nâng cấp database)
   ├─ ui/        Bộ màu chung, khung chat bong bóng, thẻ bàn, biểu đồ, lưới tự co giãn
   ├─ admin/     Quản lý thực đơn, combo, bàn, nhân viên, thống kê, cài đặt
   └─ *.java     Màn hình: ServerAdminFrame, ReceptionFrame, KitchenFrame, WaiterFrame, TabletClientFrame và các hộp thoại
```
