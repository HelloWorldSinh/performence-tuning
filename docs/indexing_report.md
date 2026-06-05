# Indexing Performance Report

> **Scope:** B-Tree Index, Composite Index, Covering Index
>
> **Out of scope:** Offset Pagination vs Keyset Pagination (handled by another team member)

**Environment:**
- Database: PostgreSQL 16, Docker
- Data scale: 100,000 users · 50,000 products · 3,000,000 orders · 10,000,000 order_items
- JVM: Java 21, Spring Boot 4.0.6

---

## Case 1 — B-Tree Index: Order Items Lookup

### 1. Business Problem

Customer Support or the Order Detail screen needs to retrieve all items belonging to a specific order. The `order_items` table has 10,000,000 rows. Without an index on `order_id`, the database must scan a massive amount of data to find a few matching rows.

### 2. Traditional Approach

**Setup — drop index to simulate no index:**

```sql
DROP INDEX IF EXISTS idx_order_items_order_id;
ANALYZE order_items;
```

**Query:**

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT order_item_id, order_id, product_id, quantity, price_per_unit
FROM order_items
WHERE order_id = 100;
```

### 3. Problem

- PostgreSQL uses **Parallel Seq Scan** on `order_items` — scanning all 10,000,000 rows.
- The query only needs a few rows for `order_id = 100`, but the engine must check every row.
- **Rows Removed by Filter: 3,333,332** — millions of rows read and discarded.
- High buffer usage: **shared hit=106 read=83,302** (83,408 total page reads).

### 4. Optimization

Create a B-Tree index on `order_id`:

```sql
CREATE INDEX IF NOT EXISTS idx_order_items_order_id
ON order_items(order_id);
```

### 5. Optimized Query

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT order_item_id, order_id, product_id, quantity, price_per_unit
FROM order_items
WHERE order_id = 100;
```

### 6. Execution Result

| Metric | Before | After |
|--------|-------:|------:|
| Execution Time | **252.3 ms** | **0.10 ms** |
| Speedup | — | **2523x** |
| Scan Type | Parallel Seq Scan | Index Scan |
| Index Used | — | `idx_order_items_order_id` |
| Buffers | 83,408 pages | 7 pages |

### 7. EXPLAIN Analysis

**Before — Parallel Seq Scan:**

```
Parallel Seq Scan on order_items
  Filter: (order_id = 100)
  Rows Removed by Filter: 3,333,332
  Buffers: shared hit=106 read=83302
```

- PostgreSQL scans the entire `order_items` table (10M rows).
- Each worker process reads a portion of the table and filters out non-matching rows.
- Over 3.3 million rows are read and discarded — pure waste.

**After — Index Scan:**

```
Index Scan using idx_order_items_order_id on order_items
  Index Cond: (order_id = 100)
  Buffers: shared hit=3 read=4
```

- PostgreSQL traverses the B-Tree index to locate the exact leaf pages for `order_id = 100`.
- Only 7 buffer pages are read (down from 83,408).
- No rows are wasted — every row read is a matching row.

### 8. Conclusion

A single-column B-Tree index on `order_id` eliminates full table scans for item lookups. The query goes from scanning 10M rows to reading 7 pages — a **2523x** improvement. This is the most impactful index in the dataset because `order_items` is the largest table.

---

## Case 2 — Composite Index: Pending Orders by Status and Date

### 1. Business Problem

The Admin/Fraud team needs to see the most recent PENDING orders to process them in priority order. The query filters by `status` and sorts by `order_date DESC`. With 3,000,000 orders, this requires both filtering and sorting at scale.

### 2. Traditional Approach

**Setup — drop composite index:**

```sql
DROP INDEX IF EXISTS idx_orders_status_date;
DROP INDEX IF EXISTS idx_orders_pending;
ANALYZE orders;
```

**Query:**

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT order_id, user_id, order_date, total_amount, status
FROM orders
WHERE status = 'PENDING'
ORDER BY order_date DESC
LIMIT 20;
```

### 3. Problem

- PostgreSQL uses **Parallel Seq Scan** on `orders` — scanning all 3,000,000 rows.
- After scanning, it must filter ~570,000 PENDING rows and then sort them by `order_date DESC`.
- **Rows Removed by Filter: 809,975** — over 800K rows read and discarded.
- Buffer usage: **shared hit=9,572 read=45,932** (55,504 total).

### 4. Optimization

Create a composite index on `(status, order_date DESC)`:

```sql
CREATE INDEX IF NOT EXISTS idx_orders_status_date
ON orders(status, order_date DESC);
```

### 5. Optimized Query

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT order_id, user_id, order_date, total_amount, status
FROM orders
WHERE status = 'PENDING'
ORDER BY order_date DESC
LIMIT 20;
```

### 6. Execution Result

| Metric | Before | After |
|--------|-------:|------:|
| Execution Time | **151.3 ms** | **0.14 ms** |
| Speedup | — | **1081x** |
| Scan Type | Parallel Seq Scan | Index Scan |
| Index Used | — | `idx_orders_status_date` |
| Buffers | 55,504 pages | 23 pages |

### 7. EXPLAIN Analysis

**Before — Parallel Seq Scan:**

```
Parallel Seq Scan on orders
  Filter: ((status)::text = 'PENDING'::text)
  Rows Removed by Filter: 809,975
  Buffers: shared hit=9572 read=45932
```

- Scans all 3M rows, filters out 810K non-matching rows.
- An additional Sort step is required to order results by `order_date DESC`.

**After — Index Scan:**

```
Index Scan using idx_orders_status_date on orders
  Index Cond: ((status)::text = 'PENDING'::text)
  Buffers: shared hit=9 read=14
```

- The composite index `(status, order_date DESC)` supports both `WHERE status = 'PENDING'` and `ORDER BY order_date DESC` in a single index scan.
- No separate Sort step needed — the index is already ordered correctly.
- Only 23 buffer pages read (down from 55,504).

### 8. Conclusion

A composite index on `(status, order_date DESC)` eliminates both the full table scan and the sort step. The planner reads the index in order, fetches the first 20 matching rows, and stops. This is a **1081x** improvement — the composite index handles filter + sort in one pass.

---

## Case 3 — Composite Index: User Order History

### 1. Business Problem

When a user opens their order history screen, the system must display the most recent orders for that user. The query filters by `user_id` and sorts by `order_date DESC`. With 3,000,000 orders spread across 100,000 users, this requires efficient per-user lookups.

### 2. Traditional Approach

**Setup — drop composite and covering indexes:**

```sql
DROP INDEX IF EXISTS idx_orders_user_date;
DROP INDEX IF EXISTS idx_orders_user_date_covering;
ANALYZE orders;
```

**Query:**

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT order_id, user_id, order_date, total_amount, status
FROM orders
WHERE user_id = 42
ORDER BY order_date DESC
LIMIT 20;
```

### 3. Problem

- PostgreSQL uses **Parallel Seq Scan** on `orders` — scanning all 3,000,000 rows.
- Without an index on `user_id`, the database must check every row to find those belonging to user 42.
- A separate Sort step is needed to order results by `order_date DESC`.
- **Rows Removed by Filter: 999,991** — nearly 1M rows read and discarded.
- Buffer usage: **shared hit=9,474 read=46,030** (55,504 total).

### 4. Optimization

Create a composite index on `(user_id, order_date DESC)`:

```sql
CREATE INDEX IF NOT EXISTS idx_orders_user_date
ON orders(user_id, order_date DESC);
```

### 5. Optimized Query

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT order_id, user_id, order_date, total_amount, status
FROM orders
WHERE user_id = 42
ORDER BY order_date DESC
LIMIT 20;
```

### 6. Execution Result

| Metric | Before | After |
|--------|-------:|------:|
| Execution Time | **290.5 ms** | **0.39 ms** |
| Speedup | — | **745x** |
| Scan Type | Parallel Seq Scan | Index Scan |
| Index Used | — | `idx_orders_user_date` |
| Buffers | 55,504 pages | 26 pages |

### 7. EXPLAIN Analysis

**Before — Parallel Seq Scan:**

```
Parallel Seq Scan on orders
  Filter: (user_id = 42)
  Rows Removed by Filter: 999,991
  Buffers: shared hit=9474 read=46030
```

- Scans all 3M rows, filters out 999,991 rows that don't belong to user 42.
- Requires an additional Sort step for `ORDER BY order_date DESC`.

**After — Index Scan:**

```
Index Scan using idx_orders_user_date on orders
  Index Cond: (user_id = 42)
  Buffers: shared hit=12 read=14
```

- The composite index `(user_id, order_date DESC)` filters by `user_id` and returns results already sorted by `order_date DESC`.
- No Sort step needed — the index maintains this order.
- Only 26 buffer pages read (down from 55,504).

### 8. Conclusion

A composite index on `(user_id, order_date DESC)` provides both filtering and ordering in a single index scan. The **leftmost prefix** rule means the index is used for `WHERE user_id = ?`, and the `order_date DESC` suffix eliminates the Sort step. This yields a **745x** improvement for per-user order history queries.

---

## Case 4 — Covering Index: User Order Projection

### 1. Business Problem

The order history screen only needs to display four columns: `user_id`, `order_date`, `total_amount`, `status`. A normal composite index on `(user_id, order_date DESC)` can find the rows quickly, but PostgreSQL still needs to go back to the table (heap) to read `total_amount` and `status`. A **covering index** can include these columns directly in the index, eliminating heap access entirely.

### 2. Traditional Approach

**Setup — drop covering index, keep normal composite index:**

```sql
DROP INDEX IF EXISTS idx_orders_user_date_covering;

CREATE INDEX IF NOT EXISTS idx_orders_user_date
ON orders(user_id, order_date DESC);

ANALYZE orders;
```

**Query:**

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT user_id, order_date, total_amount, status
FROM orders
WHERE user_id = 42
ORDER BY order_date DESC
LIMIT 20;
```

### 3. Problem

- The normal composite index `(user_id, order_date DESC)` helps find rows quickly via **Index Scan**.
- However, the query also needs `total_amount` and `status` — columns **not in the index**.
- PostgreSQL must follow each index entry's pointer to the heap page to read the extra columns.
- This adds random I/O for each row retrieved.

### 4. Optimization

Create a covering index with `INCLUDE`:

```sql
CREATE INDEX IF NOT EXISTS idx_orders_user_date_covering
ON orders(user_id, order_date DESC)
INCLUDE (total_amount, status);
```

Then update the visibility map:

```sql
VACUUM ANALYZE orders;
```

> **Why VACUUM?** Index Only Scan relies on the visibility map to determine if a row is visible without checking the heap. `VACUUM` updates the visibility map. Without it, PostgreSQL may still fetch from the heap, resulting in `Heap Fetches > 0`.

### 5. Optimized Query

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT user_id, order_date, total_amount, status
FROM orders
WHERE user_id = 42
ORDER BY order_date DESC
LIMIT 20;
```

### 6. Execution Result

| Metric | Before (Index Scan) | After (Index Only Scan) |
|--------|--------------------:|------------------------:|
| Execution Time | **0.110 ms** | **0.062 ms** |
| Speedup | — | **1.8x** |
| Scan Type | Index Scan | Index Only Scan |
| Index Used | `idx_orders_user_date` | `idx_orders_user_date_covering` |
| Heap Fetches | N/A (accesses heap) | **0** |

### 7. EXPLAIN Analysis

**Before — Index Scan (with heap access):**

```
Index Scan using idx_orders_user_date on orders
  Index Cond: (user_id = 42)
  Buffers: shared hit=12 read=14
```

- PostgreSQL traverses the index to find matching rows.
- For each row, it follows the pointer to the heap page to read `total_amount` and `status`.
- This is already fast (0.110 ms) because the composite index narrows the search to ~30 rows.

**After — Index Only Scan (no heap access):**

```
Index Only Scan using idx_orders_user_date_covering on orders
  Index Cond: (user_id = 42)
  Heap Fetches: 0
  Buffers: shared hit=9 read=3
```

- **`Heap Fetches: 0`** — PostgreSQL reads all needed data directly from the index leaf pages.
- No heap access at all — `total_amount` and `status` are stored in the index via `INCLUDE`.
- Buffer usage drops from 26 to 12 pages.

### 8. Conclusion

The covering index achieves **Index Only Scan** with `Heap Fetches: 0`, meaning PostgreSQL never touches the table. The speedup is **1.8x** — modest because the base query was already fast with the composite index. The real benefit appears at scale: when the table is much larger or the query runs thousands of times per second, avoiding heap access saves significant I/O.

**Trade-off:** The covering index is 162 MB vs 90 MB for the normal composite index (+80% storage). Use it only for high-frequency queries where the extra storage cost is justified.

---

## How to Read EXPLAIN ANALYZE

| Term | Meaning |
|------|---------|
| **Execution Time** | Wall-clock time for the query (includes planning). |
| **Seq Scan** | Reads every row in the table from start to end. |
| **Parallel Seq Scan** | Multiple workers each scan a portion of the table concurrently. |
| **Index Scan** | Uses the index to find row locations, then reads from the heap/table. |
| **Index Only Scan** | Reads all needed data from the index only — no heap access if `Heap Fetches = 0`. |
| **Heap Fetches** | Number of times PostgreSQL had to go back to the table to read data not in the index. `0` means perfect Index Only Scan. |
| **Buffers: shared hit** | Pages found in memory (buffer pool/cache). |
| **Buffers: shared read** | Pages read from disk. |
| **Rows Removed by Filter** | Rows scanned but discarded because they didn't match the WHERE clause. High values indicate a missing index. |
| **Index Cond** | The condition used to search the index (more efficient than Filter). |
| **Filter** | A condition applied after reading the row (less efficient than Index Cond). |

---

## Summary Table

| Case | Business Problem | Traditional Plan | Optimized Plan | Before (ms) | After (ms) | Speedup | Index |
|------|-----------------|------------------|----------------|------------:|-----------:|--------:|-------|
| B-Tree | Order items lookup | Parallel Seq Scan | Index Scan | 252.3 | 0.10 | **2523x** | `order_items(order_id)` |
| Composite | Pending orders | Parallel Seq Scan | Index Scan | 151.3 | 0.14 | **1081x** | `(status, order_date DESC)` |
| Composite | User order history | Parallel Seq Scan | Index Scan | 290.5 | 0.39 | **745x** | `(user_id, order_date DESC)` |
| Covering | User projection | Index Scan | Index Only Scan | 0.110 | 0.062 | **1.8x** | `INCLUDE(total_amount, status)` |

---

## Index Definitions

| # | Index Name | Table | Definition | Type |
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

## Trade-offs

### SELECT is faster, INSERT/UPDATE is slower

Each INSERT into `orders` must update **7 indexes**. Each UPDATE on indexed columns triggers index maintenance.

| Scenario | Recommendation |
|----------|---------------|
| OLTP (many INSERT/UPDATE) | Only create indexes that are actively used |
| OLAP / Read-heavy | Can add more indexes, including covering indexes |
| Large table + few queries | Prefer partial indexes to reduce overhead |
| Small table | Index overhead is negligible |

### Storage

| Object | Size |
|--------|------|
| `orders` table | 433 MB |
| Total indexes on `orders` | 469 MB |
| **Index/table ratio** | **108%** |

Indexes can consume more storage than the table itself.

---

## CLI Usage

```bash
.\gradlew.bat bootRun
```

| Menu | Action |
|------|--------|
| 4 | B-Tree — Traditional query WITHOUT index |
| 5 | B-Tree — Optimized query WITH index |
| 6 | Composite — Traditional query WITHOUT index |
| 7 | Composite — Optimized query WITH index |
| 8 | Covering — Traditional query WITHOUT covering index |
| 9 | Covering — Optimized query WITH covering index |
| 10 | Run full Indexing comparison report |
