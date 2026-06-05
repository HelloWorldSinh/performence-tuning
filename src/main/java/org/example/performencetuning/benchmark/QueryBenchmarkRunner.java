package org.example.performencetuning.benchmark;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.*;

@Component
public class QueryBenchmarkRunner {

    private static final Logger log = LoggerFactory.getLogger(QueryBenchmarkRunner.class);

    @Autowired
    private DataSource dataSource;

    // Index names used in this benchmark
    private static final String[] INDEX_NAMES = {
        "idx_orders_user_id",
        "idx_order_items_order_id",
        "idx_order_items_product_id",
        "idx_orders_status",
        "idx_orders_order_date",
        "idx_orders_user_date",
        "idx_orders_status_date",
        "idx_orders_pending",
        "idx_orders_user_date_covering"
    };

    private static final String COVERING_INDEX_SQL =
        "CREATE INDEX IF NOT EXISTS idx_orders_user_date_covering " +
        "ON orders(user_id, order_date DESC) INCLUDE (total_amount, status)";

    private static final String COVERING_QUERY =
        "SELECT user_id, order_date, total_amount, status " +
        "FROM orders WHERE user_id = 42 ORDER BY order_date DESC LIMIT 20";

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Run all query benchmarks WITHOUT secondary indexes.
     * Drops any existing indexes first to ensure clean baseline.
     */
    public void runBenchmarksWithoutIndexes() throws Exception {
        System.out.println("\n==============================================================");
        System.out.println("   QUERY BENCHMARK - KHONG CO INDEX PHU (BASELINE)");
        System.out.println("==============================================================");

        dropAllSecondaryIndexes();
        runAllQueryBenchmarks();
    }

    /**
     * Run all query benchmarks WITH secondary indexes.
     * Creates indexes first if they don't exist.
     */
    public void runBenchmarksWithIndexes() throws Exception {
        System.out.println("\n==============================================================");
        System.out.println("   QUERY BENCHMARK - CO INDEX PHU");
        System.out.println("==============================================================");

        createAllIndexes();
        runCoveringIndexComparison();
        runAllQueryBenchmarks();
    }

    /**
     * Compare OFFSET vs cursor-based pagination.
     */
    public void runPaginationComparison() throws Exception {
        System.out.println("\n==============================================================");
        System.out.println("   SO SANH PHAN TRANG: OFFSET vs CURSOR (KEYSET)");
        System.out.println("==============================================================");

        createAllIndexes();

        int pageSize = 20;
        long[] offsets = {0, 9_900, 99_900, 999_900};

        System.out.printf("%nPage size: %d rows%n", pageSize);
        System.out.println("-".repeat(70));

        // OFFSET pagination
        System.out.println("\n[1] OFFSET PAGINATION (ORDER BY order_id LIMIT ? OFFSET ?)");
        System.out.printf("%-15s %12s %15s %12s%n", "Page", "Offset", "Time (ms)", "Rows");
        System.out.println("-".repeat(58));

        for (long offset : offsets) {
            long page = (offset / pageSize) + 1;
            double time = benchmarkOffsetPagination(pageSize, offset);
            System.out.printf("  Page %-9d %,12d %15.1f %12d%n", page, offset, time, pageSize);
        }

        // Cursor pagination
        System.out.println("\n[2] CURSOR PAGINATION (WHERE order_id > ? ORDER BY order_id LIMIT ?)");
        System.out.printf("%-15s %15s %15s %12s%n", "Position", "Last ID", "Time (ms)", "Rows");
        System.out.println("-".repeat(60));

        // Simulate being at equivalent positions
        long[] cursorIds = {0, 9_901, 99_901, 999_901};
        for (long cursorId : cursorIds) {
            double time = benchmarkCursorPagination(pageSize, cursorId);
            System.out.printf("  After ID %-5d %,15d %15.1f %12d%n", cursorId, cursorId, time, pageSize);
        }

        System.out.println("\n" + "=".repeat(70));
        System.out.println("NHAN XET: OFFSET pagination cham dan khi offset tang lon.");
        System.out.println("          CURSOR pagination toc do on dinh vi chi scan can thiet.");
        System.out.println("=".repeat(70));
    }

    // ========================================================================
    // Core benchmark methods
    // ========================================================================

    private void runAllQueryBenchmarks() throws Exception {
        System.out.println("\nDang chay cac truy van benchmark...\n");

        // Query 1: Orders by user_id
        benchmarkWithParams("Orders by user_id",
            "SELECT * FROM orders WHERE user_id = ? ORDER BY order_date DESC LIMIT 20",
            5, ps -> ps.setLong(1, 42L));

        // Query 2: Orders by status
        benchmarkNoParams("Orders by status = 'PENDING'",
            "SELECT * FROM orders WHERE status = 'PENDING' ORDER BY order_date DESC LIMIT 20",
            5);

        // Query 3: Orders by date range
        benchmarkWithParams("Orders by date range",
            "SELECT * FROM orders WHERE order_date >= ? AND order_date < ? ORDER BY order_date DESC LIMIT 20",
            5, ps -> {
                ps.setTimestamp(1, Timestamp.valueOf("2025-01-01 00:00:00"));
                ps.setTimestamp(2, Timestamp.valueOf("2025-02-01 00:00:00"));
            });

        // Query 4: Order items by order_id
        benchmarkWithParams("Order items by order_id",
            "SELECT * FROM order_items WHERE order_id = ? ORDER BY order_item_id",
            5, ps -> ps.setLong(1, 100L));

        // Query 5: Join orders + order_items
        benchmarkWithParams("Join: orders + order_items for user",
            "SELECT o.order_id, o.order_date, o.total_amount, o.status, " +
            "oi.product_id, oi.quantity, oi.price_per_unit " +
            "FROM orders o JOIN order_items oi ON o.order_id = oi.order_id " +
            "WHERE o.user_id = ? ORDER BY o.order_date DESC LIMIT 50",
            5, ps -> ps.setLong(1, 42L));

        // Query 6: Count orders by status
        benchmarkNoParams("Count orders by status",
            "SELECT status, COUNT(*) FROM orders GROUP BY status ORDER BY status",
            3);

        // Query 7: Pending orders (uses partial index)
        benchmarkNoParams("Pending orders (recent)",
            "SELECT * FROM orders WHERE status = 'PENDING' ORDER BY order_date DESC LIMIT 20",
            5);

        // Query 8: Covering index / Index Only Scan
        benchmarkNoParams("Covering index: user order history",
            COVERING_QUERY,
            5);

        // Show EXPLAIN ANALYZE for key queries
        System.out.println("\n" + "=".repeat(70));
        System.out.println("EXPLAIN ANALYZE - Cac truy van quan trong");
        System.out.println("=".repeat(70));

        explainQuery("Orders by user_id",
            "SELECT * FROM orders WHERE user_id = 42 ORDER BY order_date DESC LIMIT 20");

        explainQuery("Orders by status = 'PENDING'",
            "SELECT * FROM orders WHERE status = 'PENDING' ORDER BY order_date DESC LIMIT 20");

        explainQuery("Join: orders + order_items for user",
            "SELECT o.order_id, o.order_date, o.total_amount, o.status, " +
            "oi.product_id, oi.quantity, oi.price_per_unit " +
            "FROM orders o JOIN order_items oi ON o.order_id = oi.order_id " +
            "WHERE o.user_id = 42 ORDER BY o.order_date DESC LIMIT 50");

        explainQuery("Covering index: user order history",
            COVERING_QUERY);
    }

    private void runCoveringIndexComparison() throws Exception {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("COVERING INDEX COMPARISON - Index Scan vs Index Only Scan");
        System.out.println("=".repeat(70));

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            System.out.println("\n[Before] Without covering index (regular index may need heap access)");
            stmt.execute("DROP INDEX IF EXISTS idx_orders_user_date_covering");
            stmt.execute("ANALYZE orders");
            benchmarkNoParams("Covering query before INCLUDE", COVERING_QUERY, 5);
            explainQuery("Before covering index", COVERING_QUERY);

            System.out.println("\n[After] With covering index idx_orders_user_date_covering");
            stmt.execute(COVERING_INDEX_SQL);
            stmt.execute("ANALYZE orders");
            benchmarkNoParams("Covering query after INCLUDE", COVERING_QUERY, 5);
            explainQuery("After covering index", COVERING_QUERY);
        }
    }

    // ========================================================================
    // Query execution helpers
    // ========================================================================

    @FunctionalInterface
    private interface ParameterBinder {
        void bind(PreparedStatement ps) throws Exception;
    }

    private void benchmarkNoParams(String label, String sql, int iterations) throws Exception {
        long totalTime = 0;
        long minTime = Long.MAX_VALUE;
        long maxTime = 0;

        try (Connection conn = dataSource.getConnection()) {
            // Warm up
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) { /* drain */ }
            }

            // Benchmark
            for (int i = 0; i < iterations; i++) {
                long start = System.nanoTime();
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    while (rs.next()) { /* drain */ }
                }
                long elapsed = (System.nanoTime() - start) / 1_000_000;
                totalTime += elapsed;
                minTime = Math.min(minTime, elapsed);
                maxTime = Math.max(maxTime, elapsed);
            }
        }

        double avgTime = (double) totalTime / iterations;
        System.out.printf("  %-35s avg=%6.1f ms  min=%4d ms  max=%4d ms  (%d runs)%n",
            label, avgTime, minTime, maxTime, iterations);
    }

    private void benchmarkWithParams(String label, String sql, int iterations, ParameterBinder binder) throws Exception {
        long totalTime = 0;
        long minTime = Long.MAX_VALUE;
        long maxTime = 0;

        try (Connection conn = dataSource.getConnection()) {
            // Warm up
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                binder.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) { /* drain */ }
                }
            }

            // Benchmark
            for (int i = 0; i < iterations; i++) {
                long start = System.nanoTime();
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    binder.bind(ps);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) { /* drain */ }
                    }
                }
                long elapsed = (System.nanoTime() - start) / 1_000_000;
                totalTime += elapsed;
                minTime = Math.min(minTime, elapsed);
                maxTime = Math.max(maxTime, elapsed);
            }
        }

        double avgTime = (double) totalTime / iterations;
        System.out.printf("  %-35s avg=%6.1f ms  min=%4d ms  max=%4d ms  (%d runs)%n",
            label, avgTime, minTime, maxTime, iterations);
    }

    private void explainQuery(String label, String sql) {
        System.out.println("\n--- " + label + " ---");
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " + sql)) {
            while (rs.next()) {
                System.out.println("  " + rs.getString(1));
            }
        } catch (Exception e) {
            System.out.println("  Loi: " + e.getMessage());
        }
    }

    // ========================================================================
    // Pagination benchmarks
    // ========================================================================

    private double benchmarkOffsetPagination(int pageSize, long offset) throws Exception {
        String sql = "SELECT order_id, user_id, order_date, total_amount, status " +
                     "FROM orders ORDER BY order_id LIMIT ? OFFSET ?";

        // Warm up
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, pageSize);
            ps.setLong(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) { /* drain */ }
            }
        }

        // Measure
        long total = 0;
        int runs = 3;
        for (int i = 0; i < runs; i++) {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, pageSize);
                ps.setLong(2, offset);
                long start = System.nanoTime();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) { /* drain */ }
                }
                total += (System.nanoTime() - start) / 1_000_000;
            }
        }
        return (double) total / runs;
    }

    private double benchmarkCursorPagination(int pageSize, long cursorId) throws Exception {
        String sql = "SELECT order_id, user_id, order_date, total_amount, status " +
                     "FROM orders WHERE order_id > ? ORDER BY order_id LIMIT ?";

        // Warm up
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, cursorId);
            ps.setInt(2, pageSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) { /* drain */ }
            }
        }

        // Measure
        long total = 0;
        int runs = 3;
        for (int i = 0; i < runs; i++) {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, cursorId);
                ps.setInt(2, pageSize);
                long start = System.nanoTime();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) { /* drain */ }
                }
                total += (System.nanoTime() - start) / 1_000_000;
            }
        }
        return (double) total / runs;
    }

    // ========================================================================
    // Index management
    // ========================================================================

    private void dropAllSecondaryIndexes() {
        System.out.println("\nDang xoa tat ca index phu...");
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            for (String indexName : INDEX_NAMES) {
                try {
                    stmt.execute("DROP INDEX IF EXISTS " + indexName);
                } catch (SQLException e) {
                    // Ignore - index may not exist
                }
            }
            stmt.execute("ANALYZE orders");
            stmt.execute("ANALYZE order_items");
            System.out.println("  => Da xoa tat ca index phu.");
        } catch (Exception e) {
            System.err.println("  Loi khi xoa index: " + e.getMessage());
        }
    }

    private void createAllIndexes() {
        System.out.println("\nDang tao cac index...");
        long startTime = System.currentTimeMillis();

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_orders_user_id ON orders(user_id)");
            System.out.println("  [1/9] idx_orders_user_id");

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_order_items_order_id ON order_items(order_id)");
            System.out.println("  [2/9] idx_order_items_order_id");

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_order_items_product_id ON order_items(product_id)");
            System.out.println("  [3/9] idx_order_items_product_id");

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status)");
            System.out.println("  [4/9] idx_orders_status");

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_orders_order_date ON orders(order_date)");
            System.out.println("  [5/9] idx_orders_order_date");

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_orders_user_date ON orders(user_id, order_date DESC)");
            System.out.println("  [6/9] idx_orders_user_date");

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_orders_status_date ON orders(status, order_date DESC)");
            System.out.println("  [7/9] idx_orders_status_date");

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_orders_pending ON orders(order_date DESC) WHERE status = 'PENDING'");
            System.out.println("  [8/9] idx_orders_pending (partial)");

            stmt.execute(COVERING_INDEX_SQL);
            System.out.println("  [9/9] idx_orders_user_date_covering (covering)");

            stmt.execute("ANALYZE orders");
            stmt.execute("ANALYZE order_items");

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.printf("%n  => Tao index hoan tat trong %,d ms.%n", elapsed);

            // Show index sizes
            System.out.println("\n  Kich thuoc index:");
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT indexname, pg_size_pretty(pg_relation_size(indexrelid)) " +
                    "FROM pg_stat_user_indexes WHERE schemaname = 'public' " +
                    "AND indexname LIKE 'idx_%' ORDER BY indexname")) {
                while (rs.next()) {
                    System.out.printf("    %-35s %s%n", rs.getString(1), rs.getString(2));
                }
            }
        } catch (Exception e) {
            System.err.println("  Loi khi tao index: " + e.getMessage());
        }
    }
}
