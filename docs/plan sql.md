#### 

#### 

| Thành viên | Trách nhiệm chính & Từ khóa nghiên cứu |
| :---- | :---- |
| **Sinh** | **Từ khóa: *JDBC Batch Processing vs Single Insert, Deep Pagination vs Keyset Pagination*.** |
| **Khang** | **Từ khóa: *B-Tree / Composite / Covering Index*** |
| **Thành viên 3** | **Từ khóa: *Execution Plan (EXPLAIN / EXPLAIN ANALYZE)*, *Full Table Scan vs Index Scan*.** |
| **Thành viên 4** | ***Table Partitioning (Range/List)*, *Aggregation Performance*.** |

#### 

#### **Giai Đoạn 1: Thiết lập & "Bơm" Dữ liệu (Khởi động)**

*Giai đoạn này **Thành viên 1** đóng vai trò chủ đạo, 3 người còn lại hỗ trợ rà soát và bắt đầu đọc tài liệu.*

* **Thành viên 1:**  
  * **Bước 1:** Viết script SQL gốc tạo 4 bảng (`Users`, `Products`, `Orders`, `Order_Items`) chỉ với PK và FK.  
  * **Bước 2:** Setup project Spring Boot.  
  * **Bước 3:** Viết code dùng Datafaker sinh dữ liệu. **Nghiên cứu & Đo lường:** Chạy Single Insert 50k dòng, sau đó chuyển sang code JDBC Batch Processing đẩy nốt hàng triệu dòng còn lại. Ghi nhận thời gian chênh lệch vào báo cáo.

#### **Giai Đoạn 2: Bắt bệnh & Lấy "Mốc cơ sở" (Baseline)**

*Lúc này DB đã có chục triệu dòng. 3 người nghiên cứu bắt đầu vào cuộc để chứng minh hệ thống đang cực kỳ chậm.*

* **Thành viên 2:** Viết các API lấy danh sách đơn hàng. **Thực hành:** Cố tình dùng *Deep Pagination (Offset-Limit)* với số trang rất lớn để tạo ra API chạy mất vài giây.  
* **Thành viên 3:** Viết các câu query *Aggregation* (VD: `SUM` tổng tiền, `COUNT` sản phẩm bán ra theo tháng). Chạy thử và ghi nhận sự chậm chạp.  
* **Thành viên 4:** (Lead giai đoạn này) Dùng lệnh `EXPLAIN ANALYZE` kẹp trước các câu query của Thành viên 2 và 3\.  
  * **Nghiên cứu & Báo cáo:** Phân tích Output của EXPLAIN, chụp ảnh bằng chứng DB đang bị *Full Table Scan*.  
  * Phối hợp cùng Thành viên 1 bọc các query vào API và đo *Latency (mili-giây)* tổng thể bằng `System.currentTimeMillis()`.

#### **Giai Đoạn 3: Tiến hành Tối ưu hóa (Tuning)**

*Giai đoạn quan trọng nhất, các thành viên áp dụng từ khóa của mình để sửa chữa.*

* **Thành viên 3:** Nhận thấy bảng quá lớn, viết script `V2__Partitioning.sql`. **Thực hành:** Áp dụng *Range Partitioning* chia bảng `Orders` và `Order_Items` theo tháng/quý để thu hẹp phạm vi quét dữ liệu.  
* **Thành viên 2:** Nhận thấy query phân trang quá chậm, viết script `V3__Indexing.sql`.  
  * **Thực hành:** Đánh *B-Tree* (cho cột đơn), *Composite* (cho nhiều cột). Thiết kế 1 *Covering Index* đặc biệt để DB không cần đọc bảng vật lý.  
  * **Sửa code Java:** Xóa bỏ Offset-Limit, đổi sang logic *Keyset Pagination* (Seek method).  
* *(Thành viên 1 & 4 lúc này hỗ trợ review code và kiểm tra lỗi kết nối).*

#### **Giai Đoạn 4: Nghiệm thu & Lên Báo Cáo**

*Kiểm tra lại thành quả và đóng gói đồ án.*

* **Thành viên 4:** Chạy lại toàn bộ lệnh `EXPLAIN` ở Giai đoạn 2\.  
  * **Báo cáo:** Chụp ảnh chứng minh Database đã khôn ngoan hơn, chuyển sang dùng *Index Scan* hoặc chỉ quét trên một vùng Partition nhất định.  
* **Thành viên 3:** Đo lường lại tốc độ các câu *Aggregation*, ghi nhận thời gian giảm đáng kể nhờ Partition.  
* **Thành viên 2 & 1:** Chạy lại hàm test bằng Java. Ghi nhận API *Keyset Pagination* trả về tốc độ dưới 50ms.  
* **Cả nhóm:** Cùng nhau ráp số liệu vào "Bảng So Sánh Trước/Sau" và vẽ sơ đồ ERD minh họa.  
  * 

