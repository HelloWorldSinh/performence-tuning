# Database Setup Guide

Hướng dẫn setup PostgreSQL local cho project Performance Tuning.

## 1. Bật PostgreSQL local

### Windows (PostgreSQL Installer)

Mở Services (`Win + R` → `services.msc`), tìm **postgresql-x64-17** (hoặc version tương ứng), nhấn **Start**.

Hoặc dùng Command Prompt (Admin):

```cmd
net start postgresql-x64-17
```

### macOS (Homebrew)

```bash
brew services start postgresql@17
```

### Linux (systemd)

```bash
sudo systemctl start postgresql
```

## 2. Tạo database

Kết nối vào PostgreSQL với quyền superuser:

```bash
psql -U postgres
```

Tạo database tên `e-commerce`:

```sql
CREATE DATABASE "e-commerce";
```

> **Lưu ý:** Tên database có dấu gạch ngang (`-`), phải dùng dấu ngoặc kép.

Thoát psql:

```sql
\q
```

## 3. Chạy schema

```bash
psql -U postgres -d "e-commerce" -f sql/00_create_schema.sql
```

Kiểm tra 4 bảng đã tạo:

```bash
psql -U postgres -d "e-commerce" -c "\dt"
```

Kết quả mong đợi:

```
              List of relations
 Schema |    Name     | Type  |  Owner
--------+-------------+-------+----------
 public | order_items | table | postgres
 public | orders      | table | postgres
 public | products    | table | postgres
 public | users       | table | postgres
```

## 4. Chạy ứng dụng

### Windows

```powershell
.\gradlew.bat bootRun
```

### macOS / Linux

```bash
./gradlew bootRun
```

## 5. Nạp dữ liệu

Sau khi app khởi động, chọn menu **2** để nạp dữ liệu.

Dữ liệu mặc định:

| Bảng          | Số dòng        |
|---------------|----------------|
| users         | 100,000        |
| products      | 50,000         |
| orders        | 3,000,000      |
| order_items   | 10,000,000     |

## 6. Kiểm tra số dòng

Sau khi nạp xong, kiểm tra:

```sql
SELECT COUNT(*) FROM users;
SELECT COUNT(*) FROM products;
SELECT COUNT(*) FROM orders;
SELECT COUNT(*) FROM order_items;
```

Hoặc dùng menu **1** trong app.

## 7. Phân phối status (tùy chọn)

Sau khi nạp data, tất cả orders sẽ có `status = 'DELIVERED'` (default).

Để có phân phối realistic hơn, chạy:

```bash
psql -U postgres -d "e-commerce" -f sql/01_fill_order_status.sql
```

Kết quả mong đợi:

```
  status   | row_count
-----------+-----------
 CANCELED  |    ~150,000
 DELIVERED |  ~2,400,000
 PENDING   |    ~450,000
```

## 8. Tạo indexes (sau khi nạp data)

Sau khi nạp data và phân phối status, chạy script tạo indexes:

```bash
psql -U postgres -d "e-commerce" -f sql/02_create_indexes.sql
```

Script sẽ tạo các indexes:

| Index | Bảng | Cột | Mục đích |
|-------|-------|------|----------|
| `idx_orders_user_id` | orders | user_id | FK lookup |
| `idx_order_items_order_id` | order_items | order_id | FK lookup |
| `idx_order_items_product_id` | order_items | product_id | FK lookup |
| `idx_orders_status` | orders | status | Lọc theo trạng thái |
| `idx_orders_order_date` | orders | order_date | Truy vấn theo ngày |
| `idx_orders_user_date` | orders | user_id, order_date | "Đơn hàng gần đây của tôi" |
| `idx_orders_status_date` | orders | status, order_date | "Đơn hàng PENDING theo ngày" |
| `idx_orders_pending` | orders | order_date (partial) | Chỉ index đơn PENDING |
| `idx_orders_user_date_covering` | orders | user_id, order_date INCLUDE total_amount, status | Covering index cho Index Only Scan |

## 9. Chạy indexing demo (Business Cases)

Dùng menu trong app để demo từng bài toán nghiệp vụ:

```
===== SQL Performance Tuning CLI =====
1. Show row counts
2. Load sample data
3. Truncate all tables

===== Indexing Demo =====
4. Case 1: User order history - traditional vs optimized
5. Case 2: Pending orders - traditional vs optimized
6. Case 3: Order item lookup - traditional vs optimized
7. Case 4: Covering index - Index Scan vs Index Only Scan
8. Run all indexing demos

===== Pagination Demo =====
9. Offset vs Keyset pagination comparison

0. Exit
```

### Menu 4-7: Chạy từng case

Mỗi case sẽ:
1. Nêu bài toán nghiệp vụ
2. Chạy SQL truyền thống + EXPLAIN ANALYZE (sau khi drop index liên quan)
3. Nêu vấn đề
4. Tạo index tối ưu
5. Chạy SQL tối ưu + EXPLAIN ANALYZE
6. So sánh Before/After ms + Speedup
7. Giải thích execution plan

### Menu 8: Chạy tất cả 4 cases

**Kết quả benchmark tóm tắt (3M orders, 10M order_items):**

> Detailed benchmark uses `docs/indexing_report.md` as source of truth.

| Case | Business Problem | Before (ms) | After (ms) | Speedup |
|------|-----------------|-------------|------------|---------|
| 1 | User order history | ~290 | ~0.4 | 745x |
| 2 | Pending orders | ~151 | ~0.14 | 1081x |
| 3 | Order item lookup | ~252 | ~0.10 | 2523x |
| 4 | Covering index | ~0.11 | ~0.06 | Index Only Scan |

**Kết luận:** Index giúp các truy vấn lookup chính nhanh hơn hàng trăm đến hàng nghìn lần; covering index giúp query projection dùng `Index Only Scan`.

### Menu 9: So sanh phan trang OFFSET vs CURSOR

So sánh 2 cách phân trang:

**OFFSET pagination:**
```sql
SELECT * FROM orders ORDER BY order_id LIMIT 20 OFFSET 999900;
```
- Đơn giản nhưng chậm dần khi offset lớn
- PostgreSQL phải scan qua tất cả rows trước offset

**CURSOR (keyset) pagination:**
```sql
SELECT * FROM orders WHERE order_id > 999900 ORDER BY order_id LIMIT 20;
```
- Tốc độ ổn định bất kể vị trí
- Chỉ scan đúng số rows cần thiết

Kết quả thực tế (3M orders):

| Vị trí | OFFSET (ms) | CURSOR (ms) |
|--------|-------------|-------------|
| Page 1 (offset 0) | 1.0 | 1.3 |
| Page 496 (offset 9,900) | 11.0 | 1.0 |
| Page 4996 (offset 99,900) | 10.3 | 1.0 |
| Page 49996 (offset 999,900) | 107.0 | 1.0 |

**Kết luận:** OFFSET pagination chậm dần khi offset lớn (107ms tại page 50k).
CURSOR pagination tốc độ ổn định ~1ms bất kể vị trí.

## 10. Xóa dữ liệu (nếu cần nạp lại)

Dùng menu **3** trong app, hoặc chạy trực tiếp:

```sql
TRUNCATE TABLE order_items, orders, products, users RESTART IDENTITY CASCADE;
```

## Troubleshooting

### Lỗi: `could not connect to server`

PostgreSQL chưa bật. Xem bước 1.

### Lỗi: `database "e-commerce" does not exist`

Chưa tạo database. Xem bước 2.

### Lỗi: `relation "users" does not exist`

Chưa chạy schema. Xem bước 3.

### Lỗi: `password authentication failed`

Kiểm tra `application.properties` — username/password phải khớp với PostgreSQL config.
