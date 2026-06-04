-- =============================================================================
-- Create indexes for performance tuning
-- =============================================================================
-- Purpose:
--   Add secondary indexes to support common e-commerce query patterns:
--   - Foreign key lookups (orders by user, items by order/product)
--   - Status filtering (orders by status)
--   - Date-based queries and pagination (orders by date)
--   - Composite queries (user + date, status + date)
--   - Partial index for PENDING orders (smaller, faster)
--
-- Run AFTER data is loaded (01_fill_order_status.sql).
-- Safe to re-run: uses IF NOT EXISTS.
-- =============================================================================

-- Single column indexes for foreign key lookups
CREATE INDEX IF NOT EXISTS idx_orders_user_id ON orders(user_id);
CREATE INDEX IF NOT EXISTS idx_order_items_order_id ON order_items(order_id);
CREATE INDEX IF NOT EXISTS idx_order_items_product_id ON order_items(product_id);

-- Index for status filtering
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status);

-- Index for date-based queries and pagination ordering
CREATE INDEX IF NOT EXISTS idx_orders_order_date ON orders(order_date);

-- Composite index: user + date (for "my recent orders" queries)
CREATE INDEX IF NOT EXISTS idx_orders_user_date ON orders(user_id, order_date DESC);

-- Composite index: status + date (for "pending orders sorted by date")
CREATE INDEX IF NOT EXISTS idx_orders_status_date ON orders(status, order_date DESC);

-- Partial index: only PENDING orders (smaller, faster for this common query)
CREATE INDEX IF NOT EXISTS idx_orders_pending ON orders(order_date DESC) WHERE status = 'PENDING';

-- Covering index: includes all columns needed for "my recent orders" query
-- Enables Index Only Scan — no need to access the table (heap)
-- Columns: user_id, order_date (for WHERE + ORDER BY)
-- Included: total_amount, status (returned by query but not used for search/sort)
CREATE INDEX IF NOT EXISTS idx_orders_user_date_covering
ON orders(user_id, order_date DESC) INCLUDE (total_amount, status);

-- Update table statistics after index creation
ANALYZE users;
ANALYZE products;
ANALYZE orders;
ANALYZE order_items;

-- Display index information
SELECT
    schemaname,
    tablename,
    indexname,
    pg_size_pretty(pg_relation_size(indexrelid)) AS index_size
FROM pg_stat_user_indexes
WHERE schemaname = 'public'
ORDER BY tablename, indexname;
