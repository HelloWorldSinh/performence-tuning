# Thuyết trình — B-Tree / Composite / Covering Index

## Demo CLI — 4 Business Cases

Ứng dụng CLI cho phép chạy từng bài toán nghiệp vụ để demo trực tiếp hiệu quả của indexing. Chạy `.\gradlew.bat bootRun` rồi chọn menu 4-8.

### Case 1: User Order History — Composite Index

| | |
|---|---|
| **Bài toán** | Người dùng muốn xem lịch sử đơn hàng gần đây |
| **SQL truyền thống** | `SELECT ... FROM orders WHERE user_id=42 ORDER BY order_date DESC LIMIT 20` |
| **Vấn đề** | Parallel Seq Scan + Sort — scan 3M rows, filter 999,991 rows |
| **Tối ưu** | Composite index `(user_id, order_date DESC)` |
| **Kết quả** | ~290ms → ~0.4ms (**745x nhanh hơn**) |
| **EXPLAIN Before** | Parallel Seq Scan on orders |
| **EXPLAIN After** | Index Scan using idx_orders_user_date |

### Case 2: Pending Orders — Composite Index

| | |
|---|---|
| **Bài toán** | Admin xem đơn PENDING mới nhất |
| **SQL truyền thống** | `SELECT ... FROM orders WHERE status='PENDING' ORDER BY order_date DESC LIMIT 20` |
| **Vấn đề** | Parallel Seq Scan — scan 3M rows, filter 809,975 rows |
| **Tối ưu** | Composite index `(status, order_date DESC)` |
| **Kết quả** | ~151ms → ~0.14ms (**1081x nhanh hơn**) |
| **EXPLAIN Before** | Parallel Seq Scan on orders |
| **EXPLAIN After** | Index Scan using idx_orders_status_date |

### Case 3: Order Item Lookup — B-Tree Index

| | |
|---|---|
| **Bài toán** | Khách xem chi tiết items của đơn hàng |
| **SQL truyền thống** | `SELECT ... FROM order_items WHERE order_id=100` |
| **Vấn đề** | Parallel Seq Scan — scan 10M rows |
| **Tối ưu** | B-Tree index `(order_id)` |
| **Kết quả** | ~252ms → ~0.1ms (**2523x nhanh hơn**) |
| **EXPLAIN Before** | Parallel Seq Scan on order_items |
| **EXPLAIN After** | Index Scan using idx_order_items_order_id |

### Case 4: Covering Index — Index Only Scan

| | |
|---|---|
| **Bài toán** | Màn hình lịch sử chỉ cần user_id, order_date, total_amount, status |
| **SQL truyền thống** | `SELECT user_id, order_date, total_amount, status FROM orders WHERE user_id=42 ORDER BY order_date DESC LIMIT 20` |
| **Vấn đề** | Index Scan vẫn phải quay lại heap để lấy total_amount, status |
| **Tối ưu** | Covering index `INCLUDE (total_amount, status)` |
| **Kết quả** | ~0.11ms → ~0.06ms (**Index Only Scan, Heap Fetches: 0**) |
| **EXPLAIN Before** | Index Scan using idx_orders_user_date |
| **EXPLAIN After** | **Index Only Scan** using idx_orders_user_date_covering, Heap Fetches: 0 |

---

## Bản thuyết trình (2-3 phút, nói liên tục)

---

### Mở đầu (15 giây)

Phần của tôi là phân tích và thiết kế **index** cho hệ thống e-commerce — 3 triệu đơn hàng, 10 triệu chi tiết đơn hàng. Tôi sẽ nói về 3 loại index chính: **B-Tree single-column**, **Composite index**, và **Covering index**.

---

### Vì sao cần index? (20 giây)

Không có index, PostgreSQL phải **scan toàn bộ bảng** để tìm dữ liệu. Với 3 triệu đơn hàng, mỗi lần tìm "đơn hàng của user 42" — database phải đọc **tất cả 3 triệu dòng**, so sánh từng dòng, rồi bỏ đi 999,991 dòng không khớp. Tốn hơn 290 millisecond cho một câu truy vấn đơn giản.

Index giống như **mục lục của sách** — thay vì đọc từ trang 1 đến trang 500 để tìm "B-Tree", bạn mở mục lục, thấy trang 237, và nhảy thẳng đến đó.

---

### Loại 1: B-Tree Single-column Index (25 giây)

Đây là loại index cơ bản nhất. Cấu trúc B-Tree — cây cân bằng nhiều nhánh — cho phép tìm kiếm trong **O(log n)**. Với 3 triệu dòng, PostgreSQL chỉ cần đi qua số ít page của index để tìm ra kết quả.

Tôi tạo 5 single-column indexes:
- `user_id` trên orders — để tìm đơn hàng theo user
- `order_id` trên order_items — để tìm items theo đơn hàng
- `product_id` trên order_items — để tìm items theo sản phẩm
- `status` trên orders — để lọc theo trạng thái
- `order_date` trên orders — để truy vấn theo thời gian

**Ví dụ thực tế:** Query "tất cả items của đơn hàng 100" — trước index phải scan **10 triệu dòng**, sau index chỉ đọc **3 dòng**. Thời gian: 252ms giảm còn 0.1ms — **nhanh hơn 2,523 lần**.

---

### Loại 2: Composite Index (30 giây)

Composite index chứa **nhiều cột** trong một index. Thứ tự cột rất quan trọng — quy tắc **leftmost prefix**: chỉ dùng được index nếu query filter trên cột đầu tiên.

Tôi tạo 2 composite indexes:
1. `(user_id, order_date DESC)` — cho query "đơn hàng gần đây của tôi"
2. `(status, order_date DESC)` — cho query "đơn hàng PENDING theo ngày"

**Tại sao không dùng 2 single indexes riêng biệt?**

Với single indexes, PostgreSQL sẽ: dùng index filter user_id → lấy 30 dòng → **sort lại** theo order_date. Còn composite index: filter **và** sort trong **một lần scan duy nhất**, không cần bước sort riêng.

**Kết quả:** Query "đơn hàng của user 42, sắp xếp theo ngày" — từ 290ms giảm còn 0.39ms, **nhanh hơn 745 lần**. Và PostgreSQL chọn `Index Scan` thay vì `Parallel Seq Scan`.

---

### Loại 3: Covering Index và Index Only Scan (35 giây)

Đây là kỹ thuật nâng cao. Bình thường, index chỉ chứa **key columns** — khi query cần thêm cột khác, PostgreSQL phải quay lại bảng chính để lấy data. Gọi là **Index Scan** — vẫn phải truy cập heap.

Covering index dùng `INCLUDE` để **nhúng thêm cột payload** vào leaf nodes của B-Tree:

```sql
CREATE INDEX idx_covering ON orders(user_id, order_date DESC)
INCLUDE (total_amount, status);
```

Khi query chỉ cần 4 cột này → PostgreSQL có thể dùng **Index Only Scan** — đọc data hoàn toàn từ index, không cần truy cập bảng.

**Kết quả benchmark:** Khi chạy EXPLAIN ANALYZE cho query này, PostgreSQL đã dùng Index Only Scan với `Heap Fetches: 0` — nghĩa là không có lần truy cập bảng nào. Tất cả dữ liệu được lấy trực tiếp từ index.

**Trade-off:** Covering index tốn **162 MB** so với 90 MB của index thường — tăng 80% dung lượng. Nhưng bù lại, query nhanh hơn vì tránh được random I/O vào bảng.

---

### Tổng kết benchmark (15 giây)

Tóm lại, với các index đã thiết kế — 8 index chính cộng thêm 1 covering index thử nghiệm:

| Query | Trước | Sau | Nhanh hơn |
|-------|-------|-----|-----------|
| Đơn hàng theo user | 290 ms | 0.4 ms | **745x** |
| Đơn hàng theo status | 151 ms | 0.1 ms | **1,081x** |
| Items theo đơn hàng | 252 ms | 0.1 ms | **2,523x** |
| Join orders + items | 611 ms | 0.6 ms | **1,091x** |

Nhưng index không phải miễn phí — mỗi lần INSERT hoặc UPDATE, PostgreSQL phải duy trì tất cả các index liên quan. Nhiều index hơn đồng nghĩa chi phí ghi dữ liệu tăng lên. Ngoài ra, tổng dung lượng index trên bảng orders còn lớn hơn cả bảng data. Đó là lý do phải **chọn lọc** — chỉ tạo index cho query thực sự cần.

---

### Kết thúc (5 giây)

Phần của tôi đến đây. Tôi sẵn sàng trả lời câu hỏi.

---

---

## Bản Bullet cho Slide

### Slide 1: Mục tiêu

- Thiết kế index cho hệ thống e-commerce (3M orders, 10M items)
- 3 loại index: B-Tree, Composite, Covering
- Trọng tâm: tối ưu query patterns phổ biến

### Slide 2: Vì sao cần index?

- Không index → Seq Scan → đọc toàn bộ bảng (3M rows)
- Có index → Index Scan → chỉ đọc rows cần thiết (~20 rows)
- Index = Mục lục sách → O(log n) thay vì O(n)

### Slide 3: B-Tree Single-column Index

```
Cấu trúc: Cây cân bằng nhiều nhánh (B-Tree)
         [50]
        /    \
    [25]      [75]
    /  \      /  \
 [12] [37] [62] [87]
```

- 5 indexes: user_id, order_id, product_id, status, order_date
- Hỗ trợ: =, <, >, BETWEEN, LIKE 'abc%'
- Ví dụ: `WHERE order_id = 100` → 252ms → 0.1ms (2,523x)

### Slide 4: Composite Index

```sql
CREATE INDEX idx_orders_user_date
ON orders(user_id, order_date DESC);
```

- Thứ tự cột quan trọng: leftmost prefix rule
- `(user_id, order_date)` → filter + sort trong 1 scan
- 2 indexes: (user_id, date) và (status, date)
- Ví dụ: `WHERE user_id=42 ORDER BY date DESC` → 290ms → 0.4ms (745x)

### Slide 5: Covering Index

```sql
CREATE INDEX idx_covering ON orders(user_id, order_date DESC)
INCLUDE (total_amount, status);
```

- INCLUDE thêm cột payload vào leaf nodes
- Index Only Scan → Heap Fetches: 0 → không truy cập bảng
- Kích thước: 162 MB (vs 90 MB index thường)
- Trade-off: +80% storage để đổi lấy Index Only Scan

### Slide 6: Benchmark Results

| Query | Before | After | Speedup |
|-------|--------|-------|---------|
| Orders by user | 290 ms | 0.4 ms | 745x |
| Orders by status | 151 ms | 0.1 ms | 1,081x |
| Items by order | 252 ms | 0.1 ms | 2,523x |
| Join orders+items | 611 ms | 0.6 ms | 1,091x |

### Slide 7: Trade-off

| | Lợi | Hại |
|---|---|---|
| SELECT | Nhanh 745-2,523x | — |
| INSERT | — | Chi phí tăng do duy trì nhiều index |
| UPDATE | — | Phải update tất cả index liên quan |
| Storage | — | Tăng 108% (index > table) |

→ Chỉ tạo index cho query thực sự cần

---

## 5 Câu hỏi phản biện khó nhất

### Câu 1: Tại sao không tạo index cho MỌI cột trong bảng?

**Trả lời ngắn:**

Mỗi index có 3 chi phí:
1. **Storage:** Tổng index trên bảng orders chiếm nhiều dung lượng hơn cả bảng data
2. **INSERT/UPDATE overhead:** Mỗi lần insert hoặc update, PostgreSQL phải cập nhật tất cả index liên quan — nhiều index hơn đồng nghĩa chi phí ghi tăng lên
3. **VACUUM overhead:** Nhiều index → VACUUM phải quét nhiều cấu trúc hơn → maintenance chậm hơn

→ Chỉ tạo index cho query chiếm phần lớn workload. Dùng `pg_stat_user_indexes` để kiểm tra index nào không được sử dụng (`idx_scan = 0`) → xóa bỏ.

---

### Câu 2: Composite index (A, B) khác gì với tạo 2 index riêng (A) và (B)?

**Trả lời ngắn:**

**2 index riêng:**
- `WHERE A = 1 AND B = 2` → PostgreSQL dùng index A, filter B sau → vẫn phải sort nếu cần ORDER BY B
- Không hỗ trợ query chỉ có `WHERE B = 2`

**Composite index (A, B):**
- `WHERE A = 1 AND B = 2` → filter cả A và B trong 1 scan
- `WHERE A = 1 ORDER BY B` → filter + sort trong 1 scan, **không cần sort step**
- Không hỗ trợ query chỉ có `WHERE B = 2` (leftmost prefix rule)

→ Composite index tối ưu khi query luôn filter trên cột đầu tiên.

---

### Câu 3: Covering Index với INCLUDE khác gì với tạo composite index (A, B, C, D)?

**Trả lời ngắn:**

**Composite index (A, B, C, D):**
- Cả 4 cột đều là **key columns** → ảnh hưởng đến sort order
- B-Tree sort theo (A, B, C, D) → không thể sort theo (A, D) được
- Index lớn hơn vì phải maintain sort trên 4 cột

**Covering index (A, B) INCLUDE (C, D):**
- Chỉ A, B là key → sort theo (A, B)
- C, D là **payload** trong leaf nodes → KHÔNG ảnh hưởng sort
- Dùng cho: `WHERE A=? ORDER BY B` và chỉ SELECT A, B, C, D

→ INCLUDE tối ưu hơn vì không tăng độ phức tạp của B-Tree mà vẫn covering được query.

---

### Câu 4: Tại sao COUNT(*) không nhanh hơn dù có index?

**Trả lời ngắn:**

`SELECT COUNT(*) FROM orders` phải đếm **tất cả 3 triệu dòng**. Dùng index:
- Phải scan toàn bộ **leaf nodes** của B-Tree
- Mỗi leaf node chứa 1 entry → vẫn 3 triệu entries
- Không có shortcut → O(n) dù dùng index hay không

**Giải pháp:**
- `COUNT(*) WHERE status='PENDING'` → dùng index, nhanh hơn
- Dùng `pg_class.reltuples` cho approximate count
- Dùng materialized view cho báo cáo định kỳ

---

### Câu 5: Index có làm query chậm hơn trong trường hợp nào?

**Trả lời ngắn:**

Có, trong 3 trường hợp:

1. **Query trả về >15-20% tổng số rows:**
   - `SELECT * FROM orders WHERE status = 'DELIVERED'` (80% rows)
   - Seq Scan nhanh hơn vì phải đọc hầu hết bảng anyway
   - Index Scan phải nhảy qua lại giữa index và heap → nhiều random I/O

2. **Index không phù hợp với query:**
   - Composite index `(A, B)` cho query `WHERE B = 1` → không dùng được
   - PostgreSQL sẽ ignore index và dùng Seq Scan

3. **Bảng nhỏ (<10K rows):**
   - Cả bảng vừa trong 1 buffer pool page
   - Seq Scan đọc 1 page, Index Scan đọc index page + heap page → chậm hơn

→ Để kiểm tra: dùng `EXPLAIN (ANALYZE, BUFFERS)` và xem `Rows Removed by Filter` — nếu quá lớn, index đang hoạt động tốt.
