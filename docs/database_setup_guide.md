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

## 9. Chạy indexing demo (Before vs After)

Dùng menu trong app để demo từng bước trước/sau khi có index. Khi chạy menu 4–9, CLI sẽ hiển thị:
- Raw EXPLAIN ANALYZE output
- Parsed Execution Summary (bảng ASCII tóm tắt metrics)
- EXPLAIN Meaning (bảng giải thích từng tham số)
- Plan Interpretation (nhận xét human-readable)
- Performance comparison (so sánh before/after)

```
===== SQL Performance Tuning CLI =====
1. Show row counts
2. Load sample data
3. Truncate all tables

===== Indexing Demo: Before vs After =====
4. B-Tree Index - Traditional query WITHOUT index
5. B-Tree Index - Optimized query WITH index

6. Composite Index - Traditional query WITHOUT index
7. Composite Index - Optimized query WITH index

8. Covering Index - Traditional query WITHOUT covering index
9. Covering Index - Optimized query WITH covering index

10. Run full Indexing comparison report

0. Exit
```

### Menu 4/5: B-Tree Index trước/sau

- **Menu 4:** Drop `idx_order_items_order_id`, chạy EXPLAIN ANALYZE trên `order_items WHERE order_id = 100`
- **Menu 5:** Tạo `idx_order_items_order_id`, chạy lại, so sánh Before/After

### Menu 6/7: Composite Index trước/sau

- **Menu 6:** Drop `idx_orders_status_date`, `idx_orders_pending`, chạy EXPLAIN ANALYZE trên `orders WHERE status = 'PENDING'`
- **Menu 7:** Tạo `idx_orders_status_date`, chạy lại, so sánh Before/After

### Menu 8/9: Covering Index trước/sau

- **Menu 8:** Drop `idx_orders_user_date_covering`, giữ `idx_orders_user_date`, chạy EXPLAIN ANALYZE
- **Menu 9:** Tạo `idx_orders_user_date_covering` + `VACUUM ANALYZE`, chạy lại, kiểm tra `Heap Fetches = 0`

### Menu 10: Run full Indexing comparison report

Chạy lần lượt 4 → 5 → 6 → 7 → 8 → 9, in bảng tổng hợp.

**Kết quả benchmark tóm tắt (3M orders, 10M order_items):**

> Detailed benchmark uses `docs/indexing_report.md` as source of truth.

| Index Type | Business Problem | Before (ms) | After (ms) | Speedup |
|------------|-----------------|-------------|------------|---------|
| B-Tree | Order item lookup | ~252 | ~0.10 | 2523x |
| Composite | Pending orders | ~151 | ~0.14 | 1081x |
| Covering | User order history | ~0.11 | ~0.06 | Index Only Scan |

**Kết luận:** Index giúp các truy vấn lookup chính nhanh hơn hàng trăm đến hàng nghìn lần; covering index giúp query projection dùng `Index Only Scan`.

> Pagination (Offset vs Keyset) is out of scope for this indexing task.

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
