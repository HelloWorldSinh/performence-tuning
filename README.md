# SQL Performance Tuning — E-commerce Benchmark CLI

## Mục tiêu project

Ứng dụng Java Spring Boot CLI phục vụ môn **Performance Tuning SQL / Database Performance**. Project demo và so sánh hiệu năng các kỹ thuật tối ưu database trên hệ thống e-commerce quy mô lớn:

- **JDBC Batch Processing** vs Single Insert
- **B-Tree Index** — index đơn cột cho foreign key lookup
- **Composite Index** — index nhiều cột cho filter + sort
- **Covering Index** — `INCLUDE` clause cho Index Only Scan
- **EXPLAIN / EXPLAIN ANALYZE** — phân tích execution plan
- **Full/Parallel Seq Scan** vs **Index Scan / Index Only Scan**
- **Offset Pagination** vs **Keyset (Cursor) Pagination**

## Công nghệ sử dụng

| Công nghệ | Phiên bản | Vai trò |
|---|---|---|
| Java | 21 | Ngôn ngữ chính |
| Spring Boot | 4.0.6 | Framework |
| Spring Data JDBC | — | Truy vấn database |
| Gradle | 9.5.1 (wrapper) | Build tool |
| PostgreSQL | 16 | Database |
| Docker | — | Chạy PostgreSQL |
| DataFaker | 2.5.4 | Tạo dữ liệu mẫu |
| Lombok | — | Giảm boilerplate |

## Cấu trúc project

```
performence-tuning-team/
├── build.gradle                          # Cấu hình build, dependencies
├── settings.gradle                       # Tên project
├── gradlew / gradlew.bat                # Gradle wrapper
├── sql/
│   ├── 00_create_schema.sql              # Tạo 4 bảng: users, products, orders, order_items
│   ├── 01_fill_order_status.sql          # Phân phối status: ~80% DELIVERED, ~19% PENDING, ~1% CANCELED
│   └── 02_create_indexes.sql             # Tạo 9 indexes (B-Tree, Composite, Partial, Covering)
├── src/main/java/org/example/performencetuning/
│   ├── PerformenceTuningApplication.java # Entry point Spring Boot
│   ├── data/
│   │   ├── BenchmarkRunner.java          # CLI menu chính (CommandLineRunner)
│   │   └── DataGenerator.java            # Tạo dữ liệu giả bằng DataFaker
│   └── benchmark/
│       └── QueryBenchmarkRunner.java     # Benchmark indexing & pagination
├── src/main/resources/
│   └── application.properties            # Cấu hình kết nối PostgreSQL
└── docs/
    ├── database_setup_guide.md           # Hướng dẫn setup chi tiết
    ├── indexing_report.md                # Báo cáo indexing (EXPLAIN, benchmark)
    ├── indexing_presentation.md          # Script thuyết trình
    └── plan sql.md                       # Kế hoạch nhóm
```

### Mô tả các file chính

| File | Vai trò |
|---|---|
| `DataGenerator.java` | Tạo dữ liệu giả (users, products, orders, order_items) bằng thư viện DataFaker |
| `BenchmarkRunner.java` | CLI menu chính — hiển thị menu, điều khiển benchmark, nạp dữ liệu |
| `QueryBenchmarkRunner.java` | Chạy benchmark indexing (B-Tree, Composite, Covering) và pagination (Offset vs Keyset) |
| `sql/00_create_schema.sql` | Tạo schema 4 bảng với primary key, foreign key, check constraint |
| `sql/01_fill_order_status.sql` | Phân phối lại status order sau khi nạp data |
| `sql/02_create_indexes.sql` | Tạo 9 indexes phục vụ benchmark |
| `docs/indexing_report.md` | Báo cáo chi tiết: benchmark before/after, EXPLAIN ANALYZE, phân tích |

## Yêu cầu môi trường

- **Java 21** — kiểm tra: `java -version`
- **Docker Desktop** — để chạy PostgreSQL
- **Git** — để clone repo
- **Gradle wrapper** — đã có trong project (không cần cài Gradle riêng)

## Cấu hình database

Đọc từ `src/main/resources/application.properties`:

| Param | Giá trị |
|---|---|
| URL | `jdbc:postgresql://localhost:5432/e-commerce` |
| Database name | `e-commerce` |
| Username | `postgres` |
| Password | `123456789` |
| Port | `5432` |

> **Lưu ý:** Tên database có dấu gạch ngang (`-`). Khi dùng `psql` phải đặt trong ngoặc kép: `"e-commerce"`.

## Cách chạy PostgreSQL bằng Docker

### Khởi động container

```powershell
docker run --name perf-postgres `
  -e POSTGRES_USER=postgres `
  -e POSTGRES_PASSWORD=123456789 `
  -e POSTGRES_DB=e-commerce `
  -p 5432:5432 `
  -d postgres:16
```

### Kiểm tra container đang chạy

```powershell
docker ps
```

### Kiểm tra port 5432

```powershell
Test-NetConnection localhost -Port 5432
```

### Nếu container đã tồn tại từ trước

```powershell
docker start perf-postgres
```

## Cách tạo schema

### Cách 1: Chạy trực tiếp bằng psql

```powershell
psql -U postgres -d "e-commerce" -f sql/00_create_schema.sql
```

### Cách 2: Dùng Docker exec

```powershell
docker exec -i perf-postgres psql -U postgres -d "e-commerce" < sql/00_create_schema.sql
```

### Cách 3: Vào psql interactive rồi chạy

```powershell
docker exec -it perf-postgres psql -U postgres -d "e-commerce"
```

Sau đó trong psql:

```sql
\i /dev/stdin
```

Hoặc copy file SQL vào container trước:

```powershell
docker cp sql/00_create_schema.sql perf-postgres:/tmp/00_create_schema.sql
docker exec -it perf-postgres psql -U postgres -d "e-commerce" -f /tmp/00_create_schema.sql
```

### Kiểm tra bảng đã tạo

```powershell
docker exec -it perf-postgres psql -U postgres -d "e-commerce" -c "\dt"
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

## Cách build project

```powershell
.\gradlew.bat clean build
```

Nếu build thành công sẽ thấy:

```
BUILD SUCCESSFUL in Xs
```

## Cách chạy app CLI

```powershell
.\gradlew.bat bootRun
```

App chạy dạng **interactive CLI** — hiển thị menu, người dùng nhập số để chọn chức năng.

## Menu chức năng

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

===== Pagination Demo =====
11. Offset pagination vs Keyset pagination

0. Exit
```

## Thứ tự chạy khuyến nghị

```
Bước 1.  Start PostgreSQL (Docker)
Bước 2.  Tạo schema: psql -f sql/00_create_schema.sql
Bước 3.  Build project: .\gradlew.bat clean build
Bước 4.  Chạy app: .\gradlew.bat bootRun
Bước 5.  Menu 2 — Load sample data (100K users, 50K products, 3M orders, 10M order_items)
Bước 6.  Chạy sql/01_fill_order_status.sql để phân phối status
Bước 7.  Menu 4 → Menu 5 — Demo B-Tree Index (before → after)
Bước 8.  Menu 6 → Menu 7 — Demo Composite Index (before → after)
Bước 9.  Menu 8 → Menu 9 — Demo Covering Index (before → after)
Bước 10. Menu 10 — Chạy toàn bộ indexing comparison report
Bước 11. Menu 11 — So sánh Offset vs Keyset pagination
```

> **Quan trọng:** Muốn so sánh before/after, phải chạy **Traditional (menu 4/6/8) trước**, rồi mới chạy **Optimized (menu 5/7/9) sau**. Menu 5/7/9 sẽ tự động so sánh với kết quả trước đó.

### Chạy script phân phối status (Bước 6)

```powershell
psql -U postgres -d "e-commerce" -f sql/01_fill_order_status.sql
```

Hoặc dùng Docker:

```powershell
docker exec -i perf-postgres psql -U postgres -d "e-commerce" < sql/01_fill_order_status.sql
```

## Dữ liệu mẫu

Sau khi chạy menu 2, database sẽ có:

| Bảng | Số dòng | Ghi chú |
|---|---|---|
| `users` | 100,000 | 95% active |
| `products` | 50,000 | Giá $10–$1000 |
| `orders` | 3,000,000 | user_id 1–100,000 |
| `order_items` | 10,000,000 | 2M orders × 3 items + 1M orders × 4 items |

Phân phối status sau khi chạy `01_fill_order_status.sql`:

| Status | Tỷ lệ | Số dòng (ước tính) |
|---|---|---|
| DELIVERED | ~80% | ~2,400,000 |
| PENDING | ~19% | ~570,000 |
| CANCELED | ~1% | ~30,000 |

## Indexing demo

### B-Tree Index (Menu 4/5)

- **Bài toán:** Lấy chi tiết items của một đơn hàng
- **Query:** `SELECT ... FROM order_items WHERE order_id = 100`
- **Traditional (menu 4):** Không có index trên `order_items(order_id)` → `Parallel Seq Scan` trên 10M rows
- **Optimized (menu 5):** Tạo `idx_order_items_order_id` → `Index Scan`
- **Kỳ vọng:** Thời gian giảm từ ~250ms xuống ~0.1ms

### Composite Index (Menu 6/7)

- **Bài toán:** Admin xem đơn hàng PENDING mới nhất
- **Query:** `SELECT ... FROM orders WHERE status = 'PENDING' ORDER BY order_date DESC LIMIT 20`
- **Traditional (menu 6):** Không có index `(status, order_date DESC)` → scan + filter + sort
- **Optimized (menu 7):** Tạo `idx_orders_status_date` → `Index Scan` (filter + sort trong 1 scan)
- **Kỳ vọng:** Thời gian giảm từ ~150ms xuống ~0.14ms

### Covering Index (Menu 8/9)

- **Bài toán:** Màn hình lịch sử đơn hàng chỉ cần `user_id, order_date, total_amount, status`
- **Query:** `SELECT user_id, order_date, total_amount, status FROM orders WHERE user_id = 42 ORDER BY order_date DESC LIMIT 20`
- **Traditional (menu 8):** Có index thường nhưng vẫn phải đọc heap để lấy `total_amount, status`
- **Optimized (menu 9):** Tạo covering index `INCLUDE (total_amount, status)` → `Index Only Scan`
- **Kỳ vọng:** `Heap Fetches = 0` — không truy cập bảng chính

### Full Indexing Report (Menu 10)

Chạy lần lượt menu 4→5→6→7→8→9 và in bảng tổng hợp:

```
| Index Type | Business Problem    | Traditional Plan  | Optimized Plan     | Before ms | After ms | Speedup | Index Used                    |
|------------|--------------------|--------------------|--------------------|-----------|----------|---------|-------------------------------|
| B-Tree     | Order item lookup  | Parallel Seq Scan  | Index Scan         |     252.3 |     0.10 | 2523x   | idx_order_items_order_id      |
| Composite  | Pending orders     | Parallel Seq Scan  | Index Scan         |     151.3 |     0.14 | 1081x   | idx_orders_status_date        |
| Covering   | User order history | Index Scan         | Index Only Scan    |       0.1 |     0.06 | 1.8x    | idx_orders_user_date_covering |
```

> Số liệu benchmark có thể thay đổi tùy phần cứng. Xem chi tiết tại `docs/indexing_report.md`.

## Pagination demo (Menu 11)

So sánh 2 cách phân trang trên bảng `orders` (3M rows):

### Offset Pagination

```sql
SELECT ... FROM orders ORDER BY order_id LIMIT 20 OFFSET 999900;
```

- PostgreSQL phải scan qua tất cả rows trước offset
- Chậm dần khi offset tăng lớn

### Keyset (Cursor) Pagination

```sql
SELECT ... FROM orders WHERE order_id > 999900 ORDER BY order_id LIMIT 20;
```

- Chỉ scan đúng số rows cần thiết
- Tốc độ ổn định bất kể vị trí

Kết quả thực tế (3M orders):

| Vị trí | OFFSET (ms) | KEYSET (ms) |
|---|---|---|
| Page 1 | ~1.0 | ~1.3 |
| Page 496 | ~11.0 | ~1.0 |
| Page 4996 | ~10.3 | ~1.0 |
| Page 49996 | ~107.0 | ~1.0 |

## EXPLAIN / EXPLAIN ANALYZE

Khi chạy các menu indexing, output sẽ hiển thị kết quả `EXPLAIN (ANALYZE, BUFFERS)`. Các thông số cần chú ý:

| Thông số | Ý nghĩa |
|---|---|
| `Execution Time` | Tổng thời gian thực thi (ms) |
| `Seq Scan` / `Parallel Seq Scan` | Quét toàn bộ bảng — chậm trên bảng lớn |
| `Index Scan` | Dùng index để tìm rows, sau đó truy cập bảng |
| `Index Only Scan` | Chỉ đọc từ index — nhanh nhất |
| `Rows Removed by Filter` | Số rows bị loại bỏ — càng cao càng lãng phí |
| `Buffers: shared hit/read` | Số buffer pages đọc — thấp = tốt |
| `Heap Fetches` | Số lần truy cập bảng từ index — 0 = Index Only Scan hoàn hảo |

## Các tài liệu liên quan

| File | Nội dung |
|---|---|
| `docs/database_setup_guide.md` | Hướng dẫn setup PostgreSQL chi tiết |
| `docs/indexing_report.md` | Báo cáo indexing: benchmark, EXPLAIN, phân tích |
| `docs/indexing_presentation.md` | Script thuyết trình (2–3 phút) |
| `docs/plan sql.md` | Kế hoạch phân công nhóm |

## Lưu ý an toàn

- **Không commit data lớn** (CSV, dump file)
- **Không commit `.env`** hoặc credentials
- **Menu 3 (Truncate)** sẽ xóa toàn bộ dữ liệu — chỉ dùng khi muốn nạp lại
- **Tạo index trên bảng lớn** (3M–10M rows) có thể mất vài phút
- **`VACUUM ANALYZE`** dùng cho covering index — khác với `VACUUM FULL`
- **Không chạy `VACUUM FULL`** trong benchmark vì nó sẽ rewrite toàn bộ bảng

## Troubleshooting

| Lỗi | Nguyên nhân | Cách khắc phục |
|---|---|---|
| `could not connect to server` | PostgreSQL chưa chạy | Start Docker container hoặc PostgreSQL service |
| `database "e-commerce" does not exist` | Chưa tạo database | `CREATE DATABASE "e-commerce"` trong psql |
| `relation "users" does not exist` | Chưa chạy schema | Chạy `sql/00_create_schema.sql` |
| `password authentication failed` | Sai password | Kiểm tra `application.properties` |
| `psql: command not found` | psql chưa có trong PATH | Dùng `docker exec -it perf-postgres psql ...` |
| `docker: name already in container` | Container đã tồn tại | `docker start perf-postgres` hoặc `docker rm perf-postgres` rồi tạo lại |
| Build fail: `No matching toolchains` | Chưa cài Java 21 | Cài JDK 21 (Adoptium, Oracle, v.v.) |
| Menu 5/7/9: `Chua co ket qua before` | Chưa chạy menu traditional trước | Chạy menu 4/6/8 trước, rồi mới chạy 5/7/9 |

## Trạng thái hiện tại

- **Branch:** `KhangNM17`
- **Phần triển khai:** Indexing demo (B-Tree, Composite, Covering) + Pagination comparison
- **Chưa merge main**
