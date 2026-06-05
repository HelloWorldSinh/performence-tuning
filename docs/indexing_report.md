# Indexing Report — B-Tree / Composite / Covering Index

## Mục tiêu

Phân tích và đánh giá hiệu quả của các index trong hệ thống e-commerce (3M orders, 10M order_items). Trọng tâm: B-Tree index, Composite index, Covering index, và Partial index.

---

## 0. Business Cases — Demo CLI

Ứng dụng CLI cung cấp 4 bài toán nghiệp vụ để demo trực tiếp hiệu quả của indexing.

### Bảng tổng hợp

| Case | Business Problem | Traditional Approach | Problem | Optimization | Before ms | After ms | Speedup | EXPLAIN Before | EXPLAIN After |
|---|---|---|---|---|---:|---:|---:|---|---|
| 1 | User xem lịch sử đơn hàng gần đây | `SELECT ... FROM orders WHERE user_id=42 ORDER BY order_date DESC LIMIT 20` | Parallel Seq Scan + Sort — scan 3M rows | Composite index `(user_id, order_date DESC)` | ~290 | ~0.4 | ~745x | Parallel Seq Scan on orders, Rows Removed by Filter: 999,991 | Index Scan using idx_orders_user_date |
| 2 | Admin xem đơn PENDING mới nhất | `SELECT ... FROM orders WHERE status='PENDING' ORDER BY order_date DESC LIMIT 20` | Parallel Seq Scan — scan 3M rows, filter ~570K | Composite index `(status, order_date DESC)` | ~151 | ~0.14 | ~1081x | Parallel Seq Scan on orders, Rows Removed by Filter: 809,975 | Index Scan using idx_orders_status_date |
| 3 | Khách xem chi tiết items của đơn hàng | `SELECT ... FROM order_items WHERE order_id=100` | Parallel Seq Scan — scan 10M rows | B-Tree index `(order_id)` | ~252 | ~0.10 | ~2523x | Parallel Seq Scan on order_items, Rows Removed by Filter: 3,333,332 | Index Scan using idx_order_items_order_id |
| 4 | Covering index cho lịch sử đơn hàng | `SELECT user_id, order_date, total_amount, status FROM orders WHERE user_id=42 ORDER BY order_date DESC LIMIT 20` | Index Scan vẫn phải truy cập heap | Covering index `INCLUDE (total_amount, status)` | ~0.11 | ~0.06 | ~1.8x | Index Scan using idx_orders_user_date | **Index Only Scan** using idx_orders_user_date_covering, **Heap Fetches: 0** |

### Cách chạy

```bash
.\gradlew.bat bootRun
# Chọn menu 4-7 để chạy từng case, menu 8 để chạy tất cả
```

### Chi tiết từng case

#### Case 1 — Tìm đơn hàng của một user (Composite Index)

**Bài toán nghiệp vụ:** Người dùng muốn xem lịch sử đơn hàng gần đây của mình.

**Cách truyền thống:**
```sql
SELECT order_id, user_id, order_date, total_amount, status
FROM orders
WHERE user_id = 42
ORDER BY order_date DESC
LIMIT 20;
```

**Vấn đề:** Nếu không có index phù hợp, PostgreSQL phải quét nhiều dòng trong orders rồi sort lại theo order_date.

**Cách tối ưu:**
```sql
CREATE INDEX IF NOT EXISTS idx_orders_user_date
ON orders(user_id, order_date DESC);
```

**Giải thích:** Composite index giúp filter theo user_id và trả kết quả đã được sắp xếp theo order_date DESC, tránh sort riêng.

---

#### Case 2 — Lọc đơn hàng theo trạng thái (Composite Index)

**Bài toán nghiệp vụ:** Admin muốn xem các đơn hàng PENDING mới nhất để xử lý.

**Cách truyền thống:**
```sql
SELECT order_id, user_id, order_date, total_amount, status
FROM orders
WHERE status = 'PENDING'
ORDER BY order_date DESC
LIMIT 20;
```

**Vấn đề:** status có nhiều dòng, nếu không có index theo status + order_date thì database phải scan/filter/sort nhiều dữ liệu.

**Cách tối ưu:**
```sql
CREATE INDEX IF NOT EXISTS idx_orders_status_date
ON orders(status, order_date DESC);
```

**Giải thích:** Composite index hỗ trợ cả WHERE status và ORDER BY order_date DESC trong một index scan.

---

#### Case 3 — Lấy chi tiết items của một đơn hàng (B-Tree Index)

**Bài toán nghiệp vụ:** Khi khách hàng mở chi tiết đơn hàng, hệ thống cần lấy toàn bộ sản phẩm trong order đó.

**Cách truyền thống:**
```sql
SELECT order_item_id, order_id, product_id, quantity, price_per_unit
FROM order_items
WHERE order_id = 100;
```

**Vấn đề:** order_items có 10 triệu dòng, nếu không có index trên order_id thì phải scan toàn bảng.

**Cách tối ưu:**
```sql
CREATE INDEX IF NOT EXISTS idx_order_items_order_id
ON order_items(order_id);
```

**Giải thích:** B-Tree index trên order_id giúp tìm trực tiếp các item thuộc một order.

---

#### Case 4 — Covering Index cho lịch sử đơn hàng

**Bài toán nghiệp vụ:** Màn hình lịch sử đơn hàng chỉ cần hiển thị user_id, order_date, total_amount, status.

**Cách truyền thống:**
```sql
SELECT user_id, order_date, total_amount, status
FROM orders
WHERE user_id = 42
ORDER BY order_date DESC
LIMIT 20;
```

**Vấn đề:** Index thường có thể tìm đúng dòng, nhưng vẫn phải quay lại heap/table để lấy total_amount và status.

**Cách tối ưu:**
```sql
CREATE INDEX IF NOT EXISTS idx_orders_user_date_covering
ON orders(user_id, order_date DESC)
INCLUDE (total_amount, status);
```

**Giải thích:** Covering index chứa đủ cột query cần. PostgreSQL có thể dùng Index Only Scan. Nếu EXPLAIN hiển thị Heap Fetches = 0 thì chứng minh không cần đọc bảng chính.

---

## 1. Danh sách Index đã tạo

File: `sql/02_create_indexes.sql`

| # | Index Name | Table | Definition | Loại |
|---|------------|-------|------------|------|
| 1 | `idx_orders_user_id` | orders | `btree (user_id)` | B-Tree Single-column |
| 2 | `idx_order_items_order_id` | order_items | `btree (order_id)` | B-Tree Single-column |
| 3 | `idx_order_items_product_id` | order_items | `btree (product_id)` | B-Tree Single-column |
| 4 | `idx_orders_status` | orders | `btree (status)` | B-Tree Single-column |
| 5 | `idx_orders_order_date` | orders | `btree (order_date)` | B-Tree Single-column |
| 6 | `idx_orders_user_date` | orders | `btree (user_id, order_date DESC)` | Composite |
| 7 | `idx_orders_status_date` | orders | `btree (status, order_date DESC)` | Composite |
| 8 | `idx_orders_pending` | orders | `btree (order_date DESC) WHERE status = 'PENDING'` | Partial Index |
| 9 | `idx_orders_user_date_covering` | orders | `btree (user_id, order_date DESC) INCLUDE (total_amount, status)` | Covering Index |

---

## 2. Phân loại chi tiết

### 2.1. B-Tree Single-column Index

**Cơ chế:** B-Tree (Balanced Tree) là cấu trúc dữ liệu cân bằng, cho phép tìm kiếm O(log n). Mỗi node chứa giá trị key và con trỏ đến các node con. Leaf nodes chứa pointers đến data pages.

**Ưu điểm:**
- Hỗ trợ: `=`, `<`, `>`, `<=`, `>=`, `BETWEEN`, `IN`, `LIKE 'abc%'`
- Hỗ trợ `ORDER BY` nếu direction khớp
- Hỗ trợ `GROUP BY`

**Các index thuộc loại này:**

#### `idx_orders_user_id` — orders(user_id)
- **Kích thước:** 22 MB
- **Dùng cho:** Tìm tất cả đơn hàng của một user
- **Hỗ trợ WHERE:** ✅ `WHERE user_id = ?`
- **Hỗ trợ ORDER BY:** ❌ Không (chỉ 1 cột, không có thứ tự hữu ích cho sort khác)
- **Hỗ trợ JOIN:** ✅ `JOIN users ON orders.user_id = users.user_id`
- **Là Covering Index?** ❌ Cần truy cập bảng để lấy các cột khác

#### `idx_order_items_order_id` — order_items(order_id)
- **Kích thước:** 141 MB
- **Dùng cho:** Tìm tất cả items trong một đơn hàng
- **Hỗ trợ WHERE:** ✅ `WHERE order_id = ?`
- **Hỗ trợ ORDER BY:** ❌ Không
- **Hỗ trợ JOIN:** ✅ `JOIN orders ON order_items.order_id = orders.order_id`
- **Là Covering Index?** ❌

#### `idx_order_items_product_id` — order_items(product_id)
- **Kích thước:** 67 MB
- **Dùng cho:** Tìm tất cả items chứa một sản phẩm
- **Hỗ trợ WHERE:** ✅ `WHERE product_id = ?`
- **Hỗ trợ ORDER BY:** ❌ Không
- **Hỗ trợ JOIN:** ✅ `JOIN products ON order_items.product_id = products.product_id`
- **Là Covering Index?** ❌

#### `idx_orders_status` — orders(status)
- **Kích thước:** 20 MB
- **Dùng cho:** Lọc đơn hàng theo trạng thái
- **Hỗ trợ WHERE:** ✅ `WHERE status = 'PENDING'`
- **Hỗ trợ ORDER BY:** ❌ Không (cardinality thấp ~3 giá trị)
- **Hỗ trợ JOIN:** ❌ Không thường dùng cho JOIN
- **Là Covering Index?** ❌
- **Ghi chú:** Cardinality thấp (chỉ 3 giá trị: DELIVERED, PENDING, CANCELED). Index vẫn hữu ích vì kết hợp được với Index Only Scan cho COUNT query.

#### `idx_orders_order_date` — orders(order_date)
- **Kích thước:** 64 MB
- **Dùng cho:** Truy vấn theo khoảng thời gian, phân trang
- **Hỗ trợ WHERE:** ✅ `WHERE order_date >= ? AND order_date < ?`
- **Hỗ trợ ORDER BY:** ✅ `ORDER BY order_date DESC` (Index Scan Backward)
- **Hỗ trợ JOIN:** ❌ Không thường dùng cho JOIN
- **Là Covering Index?** ❌

---

### 2.2. Composite Index

**Cơ chế:** Composite index chứa nhiều cột, được sắp xếp theo thứ tự xác định. B-Tree được xây dựng trên toàn bộ tuple (col1, col2, ...). Nguyên tắc **leftmost prefix** áp dụng: index có thể sử dụng cho query chỉ dùng col1, hoặc col1 + col2, nhưng không thể dùng cho query chỉ dùng col2 mà không có col1.

**Quy tắc chọn thứ tự cột:**
1. Cột dùng trong `WHERE` với độ chọn lọc cao (high cardinality) đặt trước
2. Cột dùng trong `ORDER BY` đặt sau
3. Nếu có cả `WHERE` và `ORDER BY`, thứ tự `(where_col, order_col)` tối ưu vì: filter trước → sort sau không cần thêm step

**Các index thuộc loại này:**

#### `idx_orders_user_date` — orders(user_id, order_date DESC)
- **Kích thước:** 90 MB
- **Thứ tự cột:** `user_id` trước, `order_date DESC` sau
- **Lý do chọn thứ tự:**
  - `user_id` có cardinality cao (100,000 giá trị) → lọc nhanh
  - `order_date DESC` hỗ trợ sort kết quả theo ngày giảm dần
  - Query `WHERE user_id = ? ORDER BY order_date DESC` → chỉ cần 1 index scan, không cần sort step
- **Hỗ trợ WHERE:** ✅ `WHERE user_id = ?` (dùng cột đầu tiên)
- **Hỗ trợ ORDER BY:** ✅ `ORDER BY order_date DESC` (cùng direction với index)
- **Hỗ trợ JOIN:** ✅ `WHERE user_id = ?` dùng cho join condition
- **Là Covering Index?** ❌ Không (phải truy cập bảng để lấy `total_amount`, `status`)
- **Leftmost prefix:** Có thể dùng cho `WHERE user_id = ?` mà không cần order_date

#### `idx_orders_status_date` — orders(status, order_date DESC)
- **Kích thước:** 111 MB
- **Thứ tự cột:** `status` trước, `order_date DESC` sau
- **Lý do chọn thứ tự:**
  - `status` là điều kiện filter chính (`WHERE status = 'PENDING'`)
  - `order_date DESC` cho phép sort kết quả theo ngày
  - Kết hợp filter + sort trong 1 index scan
- **Hỗ trợ WHERE:** ✅ `WHERE status = ?` (dùng cột đầu tiên)
- **Hỗ trợ ORDER BY:** ✅ `ORDER BY order_date DESC`
- **Hỗ trợ JOIN:** ❌ Không thường dùng
- **Là Covering Index?** ❌
- **Ghi chú:** Index này được PostgreSQL ưu tiên hơn `idx_orders_pending` cho query `WHERE status = 'PENDING' ORDER BY order_date DESC` vì tính linh hoạt (hỗ trợ tất cả status, không chỉ PENDING)

---

### 2.3. Partial Index

**Cơ chế:** Chỉ index các hàng thỏa mãn điều kiện `WHERE`. Index nhỏ hơn, nhanh hơn cho các truy vấn thường xuyên trên tập con dữ liệu.

**Các index thuộc loại này:**

#### `idx_orders_pending` — orders(order_date DESC) WHERE status = 'PENDING'
- **Kích thước:** 12 MB (so với 111 MB của idx_orders_status_date)
- **Điều kiện:** Chỉ index hàng có `status = 'PENDING'` (~19% tổng số, ~570K rows)
- **Ưu điểm:** Nhỏ hơn 9x so với full index → ít storage, ít maintenance
- **Hỗ trợ WHERE:** ✅ `WHERE status = 'PENDING'` (implicit từ partial condition)
- **Hỗ trợ ORDER BY:** ✅ `ORDER BY order_date DESC`
- **Là Covering Index?** ❌
- **Thực tế sử dụng:** PostgreSQL ưu tiên `idx_orders_status_date` hơn vì nó linh hoạt hơn. Partial index hữu ích khi:
  - Query LUÔN filter `status = 'PENDING'`
  - Cần tiết kiệm storage
  - Có nhiều index trên bảng và muốn giảm overhead

---

### 2.4. Covering Index (với INCLUDE)

**Cơ chế:** Covering index chứa tất cả cột cần thiết cho một query, cho phép **Index Only Scan** — không cần truy cập bảng (heap). Sử dụng `INCLUDE` clause để thêm cột "payload" vào leaf nodes của B-Tree mà không ảnh hưởng đến thứ tự sort.

**Khác biệt so với Composite Index thông thường:**
- Composite index: `(col_a, col_b)` — cả 2 cột đều ảnh hưởng đến sort order
- Covering index: `(col_a, col_b) INCLUDE (col_c, col_d)` — chỉ col_a, col_b ảnh hưởng sort; col_c, col_d là payload

**Các index thuộc loại này:**

#### `idx_orders_user_date_covering` — orders(user_id, order_date DESC) INCLUDE (total_amount, status)
- **Kích thước:** 162 MB (so với 90 MB của idx_orders_user_date không có INCLUDE)
- **Columns in index:**
  - Sort columns: `user_id`, `order_date DESC`
  - Included columns: `total_amount`, `status`
- **Dùng cho:** Query chỉ cần 4 cột này → Index Only Scan
- **Hỗ trợ WHERE:** ✅ `WHERE user_id = ?`
- **Hỗ trợ ORDER BY:** ✅ `ORDER BY order_date DESC`
- **Là Covering Index?** ✅ Có — cho query `SELECT user_id, order_date, total_amount, status`
- **Trade-off:** Kích thước tăng 80% (90MB → 162MB) để đổi lấy Index Only Scan

---

## 3. Benchmark Before/After

### 3.1. Bảng so sánh chi tiết

| # | Query | Before (ms) | After (ms) | Speedup | Before Scan | After Scan | Index Used |
|---|-------|-------------|------------|---------|-------------|------------|------------|
| 1 | Orders by user_id | 290.5 | 0.39 | **745x** | Parallel Seq Scan | Index Scan | `idx_orders_user_date` |
| 2 | Orders by status = 'PENDING' | 151.3 | 0.14 | **1081x** | Parallel Seq Scan | Index Scan | `idx_orders_status_date` |
| 3 | Orders by date range | 123.9 | 0.24 | **516x** | Parallel Seq Scan | Index Scan Backward | `idx_orders_order_date` |
| 4 | Order items by order_id | 252.3 | 0.10 | **2523x** | Parallel Seq Scan | Index Scan | `idx_order_items_order_id` |
| 5 | Join orders + order_items | 611.2 | 0.56 | **1091x** | Parallel Hash Join (Seq Scan × 2) | Nested Loop (Index Scan × 2) | `idx_orders_user_date` + `idx_order_items_order_id` |
| 6 | Count by status | 242.7* | 243.8 | ~1x | Parallel Seq Scan | Parallel Index Only Scan | `idx_orders_status` |

*Ghi chú: Query 6 (COUNT) không nhanh hơn đáng kể vì phải scan toàn bộ bảng dù dùng index.

### 3.2. EXPLAIN Summary

#### Q1: Orders by user_id

**Before (290.5 ms):**
```
Parallel Seq Scan on orders
  Filter: (user_id = 42)
  Rows Removed by Filter: 999,991
  Buffers: shared hit=9474 read=46030
```
→ Phải scan toàn bộ 3M rows, filter 999,991 rows không khớp.

**After (0.39 ms):**
```
Index Scan using idx_orders_user_date on orders
  Index Cond: (user_id = 42)
  Buffers: shared hit=12 read=14
```
→ Truy cập trực tiếp 20 rows cần thiết. Buffers giảm từ 55,504 → 26.

---

#### Q2: Orders by status = 'PENDING'

**Before (151.3 ms):**
```
Parallel Seq Scan on orders
  Filter: ((status)::text = 'PENDING'::text)
  Rows Removed by Filter: 809,975
  Buffers: shared hit=9572 read=45932
```
→ Scan 3M rows, filter 810K rows.

**After (0.14 ms):**
```
Index Scan using idx_orders_status_date on orders
  Index Cond: ((status)::text = 'PENDING'::text)
  Buffers: shared hit=9 read=14
```
→ Composite index `(status, order_date DESC)` cho phép vừa filter vừa sort trong 1 scan.

---

#### Q3: Orders by date range

**Before (123.9 ms):**
```
Parallel Seq Scan on orders
  Filter: (order_date >= '2025-01-01' AND order_date < '2025-02-01')
  Rows Removed by Filter: 957,429
  Buffers: shared hit=9668 read=45836
```

**After (0.24 ms):**
```
Index Scan Backward using idx_orders_order_date on orders
  Index Cond: (order_date >= ... AND order_date < ...)
  Buffers: shared hit=5 read=18
```
→ `Index Scan Backward` vì `ORDER BY order_date DESC` — duyệt index từ mới đến cũ.

---

#### Q4: Order items by order_id

**Before (252.3 ms):**
```
Parallel Seq Scan on order_items
  Filter: (order_id = 100)
  Rows Removed by Filter: 3,333,332
  Buffers: shared hit=106 read=83302
```
→ Scan 10M rows!

**After (0.10 ms):**
```
Index Scan using idx_order_items_order_id on order_items
  Index Cond: (order_id = 100)
  Buffers: shared hit=3 read=4
```
→ Buffers giảm từ 83,408 → 7.

---

#### Q5: Join orders + order_items

**Before (611.2 ms):**
```
Parallel Hash Join
  -> Parallel Seq Scan on order_items (scan 10M rows)
  -> Parallel Seq Scan on orders (scan 3M rows, filter user_id = 42)
  Buffers: shared hit=9952 read=128946
```
→ Cả 2 bảng đều Seq Scan → tổng 138,898 buffer reads.

**After (0.56 ms):**
```
Nested Loop
  -> Index Scan using idx_orders_user_date on orders (user_id = 42)
  -> Index Scan using idx_order_items_order_id on order_items (order_id = o.order_id)
  Buffers: shared hit=37 read=44
```
→ Nested Loop thay vì Hash Join. Buffers giảm từ 138,898 → 81.

---

## 4. Covering Index Analysis

### 4.1. Index Only Scan vs Index Scan

| Scan Type | Có truy cập bảng? | Ví dụ |
|-----------|-------------------|-------|
| **Seq Scan** | ✅ Scan toàn bộ bảng | Before: không có index |
| **Index Scan** | ✅ Truy cập bảng theo pointer | After: có index nhưng query cần cột ngoài index |
| **Index Only Scan** | ❌ Chỉ đọc index | Query chỉ dùng cột có trong index |

### 4.2. Test với Covering Index

**Query:** `SELECT user_id, order_date, total_amount, status FROM orders WHERE user_id = 42 ORDER BY order_date DESC LIMIT 20`

| Index | Scan Type | Heap Fetches | Execution Time |
|-------|-----------|--------------|----------------|
| `idx_orders_user_date` (không INCLUDE) | Index Scan | N/A (truy cập heap) | 0.110 ms |
| `idx_orders_user_date_covering` (có INCLUDE) | **Index Only Scan** | **0** | 0.062 ms |

**EXPLAIN với Covering Index:**
```
Index Only Scan using idx_orders_user_date_covering on orders
  Index Cond: (user_id = 42)
  Heap Fetches: 0
  Buffers: shared hit=9 read=3
```

→ `Heap Fetches: 0` chứng minh **không truy cập bảng** — tất cả dữ liệu nằm trong index.

### 4.3. Khi nào cần Covering Index?

| Tình huống | Có cần Covering Index? |
|------------|------------------------|
| Query chỉ cần 1-2 cột đã có trong index | ❌ Không (index thường đủ) |
| Query cần thêm cột ngoài index → phải truy cập bảng | ✅ Có (tránh random I/O) |
| Bảng rất lớn, heap pages rải rác | ✅ Có (tránh nhiều page reads) |
| Query chạy rất thường xuyên (OLTP) | ✅ Có (tối ưu latency) |
| Bảng nhỏ, đủ cache | ❌ Không (heap access nhanh) |

### 4.4. Trade-off của Covering Index

| | Index thường | Covering Index (INCLUDE) |
|---|---|---|
| **Kích thước** | 90 MB | 162 MB (+80%) |
| **Index Scan** | ✅ | ✅ |
| **Index Only Scan** | ❌ (cho query cần thêm cột) | ✅ |
| **INSERT/UPDATE overhead** | Thấp | Cao hơn |
| **Dùng cho** | Filter + Sort | Filter + Sort + Retrieve |

---

## 5. Giải thích kỹ thuật

### 5.1. B-Tree Index

```
         [50]
        /    \
    [25]      [75]
    /  \      /  \
 [12] [37] [62] [87]
  ↓    ↓    ↓    ↓
 Data Data Data Data  ← Leaf nodes (doubly linked list)
```

- **Height:** O(log n) — với 3M rows, B-Tree cao ~3-4 levels
- **Leaf scan:** Doubly linked list → hỗ trợ range scan và backward scan
- **PostgreSQL implementation:** Giống heap, B-Tree pages có size 8KB

### 5.2. Composite Index

```
Index: (user_id, order_date DESC)

B-Tree structure:
(user_id=1, order_date=2025-06-01) → row pointer
(user_id=1, order_date=2025-05-15) → row pointer
(user_id=2, order_date=2025-06-03) → row pointer
...

Leftmost prefix rules:
✅ WHERE user_id = 42                    → dùng index
✅ WHERE user_id = 42 ORDER BY date DESC → dùng index, KHÔNG cần sort
❌ WHERE order_date = '2025-06-01'       → KHÔNG dùng được (thiếu user_id)
```

### 5.3. Covering Index với INCLUDE

```
CREATE INDEX idx ON orders(user_id, order_date DESC) INCLUDE (total_amount, status);

B-Tree structure:
┌─────────────────────────────────────────────────┐
│ Key: (user_id, order_date)                      │
│ Payload: (total_amount, status)                 │
│ → chỉ trong leaf nodes, KHÔNG ảnh hưởng sort    │
└─────────────────────────────────────────────────┘

Index Only Scan:
1. Traverse B-Tree → tìm leaf node
2. Đọc total_amount, status TỪ LEAF NODE
3. KHÔNG cần truy cập heap page
→ Tiết kiệm random I/O trên ổ HDD, tiết kiệm buffer pool trên SSD
```

---

## 6. Trade-off: SELECT nhanh hơn, INSERT/UPDATE chậm hơn

### 6.1. Ảnh hưởng đến INSERT

Mỗi INSERT vào bảng `orders` phải cập nhật **9 indexes:**

| Index | Cập nhật mỗi INSERT |
|-------|---------------------|
| `idx_orders_user_id` | 1 entry |
| `idx_orders_order_date` | 1 entry |
| `idx_orders_user_date` | 1 entry |
| `idx_orders_status` | 1 entry |
| `idx_orders_status_date` | 1 entry |
| `idx_orders_pending` | 0-1 entry (chỉ nếu status='PENDING') |
| `idx_orders_user_date_covering` | 1 entry |

**Ước tính overhead:** Mỗi index thêm ~0.1-0.5ms cho INSERT. Với 7 indexes trên `orders`:
- Không index: ~0.1ms/INSERT
- Có indexes: ~1-3ms/INSERT
- Batch insert 5000 rows: overhead tăng ~50-150ms/batch

### 6.2. Ảnh hưởng đến UPDATE

UPDATE trên cột có index sẽ trigger **index maintenance:**
- `UPDATE orders SET status = 'CANCELED' WHERE order_id = 123`
  → Cập nhật: `idx_orders_status`, `idx_orders_status_date`, `idx_orders_pending`, `idx_orders_user_date_covering`
  → 4 index updates cho 1 row update

### 6.3. Ảnh hưởng đến Storage

| | Dung lượng |
|---|---|
| Bảng `orders` | 433 MB |
| Tổng indexes trên `orders` | 469 MB |
| **Tỷ lệ index/table** | **108%** |

→ Index chiếm nhiều dung lượng hơn cả bảng data!

### 6.4. Hướng dẫn tối ưu

| Tình huống | Khuyến nghị |
|------------|-------------|
| OLTP (nhiều INSERT/UPDATE) | Chỉ tạo index thực sự cần thiết |
| OLAP/Read-heavy | Có thể thêm nhiều index, kể cả covering |
| Bảng lớn + ít query | Ưu tiên partial index |
| Bảng nhỏ | Index overhead không đáng kể |

---

## 7. Câu hỏi phản biện thường gặp

### Q1: Tại sao dùng Composite Index `(user_id, order_date DESC)` thay vì 2 single indexes?

**A:** Nếu dùng 2 single indexes:
```sql
CREATE INDEX idx1 ON orders(user_id);
CREATE INDEX idx2 ON orders(order_date);
```
PostgreSQL sẽ:
1. Dùng `idx1` filter `user_id = 42` → lấy ~30 rows
2. Sort kết quả theo `order_date DESC` → thêm Sort step
3. Limit 20

Với composite index:
1. Dùng `(user_id, order_date DESC)` → filter + sort trong 1 scan
2. Không cần Sort step
3. Chỉ đọc đúng 20 rows đầu tiên

**Kết quả:** Composite index nhanh hơn vì tránh được Sort step và đọc ít rows hơn.

---

### Q2: Tại sao thứ tự cột trong Composite Index quan trọng?

**A:** B-Tree sắp xếp theo thứ tự `(col1, col2, col3)`. Query chỉ có thể dùng index nếu nó filter trên **leftmost prefix:**

```sql
CREATE INDEX idx ON orders(user_id, order_date DESC);

-- ✅ Dùng được:
WHERE user_id = 42
WHERE user_id = 42 AND order_date > '2025-01-01'
WHERE user_id = 42 ORDER BY order_date DESC

-- ❌ Không dùng được:
WHERE order_date > '2025-01-01'  -- thiếu user_id
ORDER BY order_date DESC          -- không có WHERE user_id
```

---

### Q3: Tại sao PostgreSQL chọn `idx_orders_status_date` thay vì `idx_orders_pending`?

**A:** Cả 2 index đều phục vụ query `WHERE status = 'PENDING' ORDER BY order_date DESC`. PostgreSQL chọn `idx_orders_status_date` vì:
1. **Linh hoạt hơn:** Hỗ trợ tất cả status, không chỉ PENDING
2. **Cost thấp hơn:** Planner tính toán dựa trên thống kê, composite index có estimated cost tốt hơn
3. **Partial index chỉ tối ưu khi:** Query LUÔN filter PENDING và bảng rất lớn

Để force dùng partial index: `SET enable_indexscan = off;` hoặc dùng `/*+ INDEX(orders idx_orders_pending) */` (hint).

---

### Q4: Covering Index có phải lúc nào cũng tốt hơn?

**A:** Không. Trade-off:
- ✅ **Tốt hơn:** Query chạy thường xuyên, latency quan trọng, bảng lớn
- ❌ **Không tốt:** Tăng storage 80%, INSERT/UPDATE chậm hơn, maintenance overhead

Chỉ dùng Covering Index cho **top queries** chiếm phần lớn workload.

---

### Q5: Tại sao COUNT(*) không nhanh hơn dù có index?

**A:** `SELECT COUNT(*) FROM orders` phải đếm **tất cả rows**. Dù dùng index:
- Index scan vẫn phải scan toàn bộ index leaf nodes
- Không có cách nào đếm nhanh hơn O(n)

Giải pháp:
- `COUNT(*)` với filter (`WHERE status = 'PENDING'`) → dùng index, nhanh hơn
- Dùng approximate count: `SELECT reltuples FROM pg_class WHERE relname = 'orders'`
- Dùng materialized view cho báo cáo

---

### Q6: Index có ảnh hưởng đến VACUUM không?

**A:** Có. VACUUM phải quét cả bảng VÀ tất cả indexes để reclaim dead tuples. Nhiều index → VACUUM chậm hơn → có thể gây bloat nếu VACUUM không theo kịp.

---

## 8. Khuyến nghị

### Index hiện tại đã tối ưu cho:

| Query Pattern | Index | Hiệu quả |
|---------------|-------|----------|
| Đơn hàng của user | `idx_orders_user_date` | ✅ Tốt (745x nhanh hơn) |
| Đơn hàng theo status | `idx_orders_status_date` | ✅ Tốt (1081x nhanh hơn) |
| Đơn hàng theo ngày | `idx_orders_order_date` | ✅ Tốt (516x nhanh hơn) |
| Items trong đơn hàng | `idx_order_items_order_id` | ✅ Tốt (2523x nhanh hơn) |
| Join orders + items | Kết hợp 2 index | ✅ Tốt (1091x nhanh hơn) |

### Có thể xem xét thêm:

1. **Covering index cho Order Items lookup** (nếu query thường xuyên cần thêm cột):
   ```sql
   CREATE INDEX idx_order_items_order_covering
   ON order_items(order_id) INCLUDE (product_id, quantity, price_per_unit);
   ```

2. **Xóa `idx_orders_pending` nếu không dùng:**
   - PostgreSQL không dùng nó (ưu tiên composite index)
   - Tiết kiệm 12 MB storage + maintenance overhead

3. **Monitor index usage:**
   ```sql
   SELECT indexrelname, idx_scan, idx_tup_read, idx_tup_fetch
   FROM pg_stat_user_indexes
   WHERE schemaname = 'public' AND indexrelname LIKE 'idx_%';
   ```
