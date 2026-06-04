-- =============================================================================
-- Fill / Redistribute order status
-- =============================================================================
-- Purpose:
--   After the schema is created with DEFAULT 'DELIVERED', all orders inserted
--   by DataGenerator will have status = 'DELIVERED'.
--
--   Run this script to get a realistic distribution:
--     ~80% DELIVERED
--     ~15% PENDING
--     ~5%  CANCELED
--
-- Safe to re-run: redistributes all rows each time.
-- =============================================================================

-- Redistribute all order statuses
UPDATE orders
SET status = CASE
    WHEN random() < 0.80 THEN 'DELIVERED'
    WHEN random() < 0.95 THEN 'PENDING'
    ELSE 'CANCELED'
END;

-- Update table statistics for the query planner
ANALYZE orders;

-- Verify distribution
SELECT status, COUNT(*) AS row_count
FROM orders
GROUP BY status
ORDER BY status;
