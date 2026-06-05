package org.example.performencetuning.benchmark;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

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

    // Store last results for before/after comparison
    private ExplainResult lastBTreeBefore;
    private ExplainResult lastBTreeAfter;
    private ExplainResult lastCompositeBefore;
    private ExplainResult lastCompositeAfter;
    private ExplainResult lastCoveringBefore;
    private ExplainResult lastCoveringAfter;

    // ========================================================================
    // Public API — Split Before/After Demos
    // ========================================================================

    /**
     * Menu 4: B-Tree Traditional — WITHOUT index on order_items(order_id)
     */
    public void runBTreeTraditional() throws Exception {
        String sql = "SELECT order_item_id, order_id, product_id, quantity, price_per_unit\n" +
                "FROM order_items\nWHERE order_id = 100;";

        System.out.println("\n" + "=".repeat(70));
        System.out.println("CASE: B-Tree Index - Traditional WITHOUT Index");
        System.out.println("=".repeat(70));

        System.out.println("\nBusiness problem:");
        System.out.println("- Lay chi tiet items cua mot don hang.");
        System.out.println("- Khi khach hang mo chi tiet don hang, he thong can lay toan bo");
        System.out.println("  san pham trong order do.");

        System.out.println("\nSQL:");
        System.out.println(sql);

        // Drop B-Tree index
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP INDEX IF EXISTS idx_order_items_order_id");
            stmt.execute("ANALYZE order_items");
            System.out.println("\n[Index dropped: idx_order_items_order_id]");
        }

        lastBTreeBefore = runExplainAnalyze(sql);

        System.out.println("\nEXPLAIN ANALYZE:");
        for (String line : lastBTreeBefore.outputLines) {
            System.out.println("  " + line);
        }

        System.out.printf("%nExecution time: %.1f ms%n", lastBTreeBefore.executionTimeMs);

        // Parse plan summary
        printPlanSummary(lastBTreeBefore, false, null);

        System.out.println("\nExpected issue:");
        System.out.println("- Seq Scan / Parallel Seq Scan on 10M order_items");
        System.out.println("- order_items co 10 trieu dong, neu khong co index tren order_id");
        System.out.println("  thi phai scan toan bang.");
        System.out.println("=".repeat(70));
    }

    /**
     * Menu 5: B-Tree Optimized — WITH index on order_items(order_id)
     */
    public void runBTreeOptimized() throws Exception {
        String sql = "SELECT order_item_id, order_id, product_id, quantity, price_per_unit\n" +
                "FROM order_items\nWHERE order_id = 100;";

        System.out.println("\n" + "=".repeat(70));
        System.out.println("CASE: B-Tree Index - Optimized WITH Index");
        System.out.println("=".repeat(70));

        System.out.println("\nBusiness problem:");
        System.out.println("- Lay chi tiet items cua mot don hang.");

        // Create B-Tree index
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            System.out.println("\nDang tao index idx_order_items_order_id...");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_order_items_order_id ON order_items(order_id)");
            stmt.execute("ANALYZE order_items");
            System.out.println("[Index created: idx_order_items_order_id]");
        }

        System.out.println("\nIndex created:");
        System.out.println("CREATE INDEX IF NOT EXISTS idx_order_items_order_id");
        System.out.println("ON order_items(order_id);");

        System.out.println("\nSQL:");
        System.out.println(sql);

        ExplainResult afterResult = runExplainAnalyze(sql);
        lastBTreeAfter = afterResult;

        System.out.println("\nEXPLAIN ANALYZE:");
        for (String line : afterResult.outputLines) {
            System.out.println("  " + line);
        }

        System.out.printf("%nExecution time: %.1f ms%n", afterResult.executionTimeMs);

        // Parse plan summary
        String indexUsed = printPlanSummary(afterResult, true, "B-Tree index tren order_id giup tim truc tiep cac item thuoc mot order.");

        // Before/After comparison
        printBeforeAfterComparison(lastBTreeBefore, afterResult, indexUsed);
        System.out.println("=".repeat(70));
    }

    /**
     * Menu 6: Composite Traditional — WITHOUT composite index on orders(status, order_date)
     */
    public void runCompositeTraditional() throws Exception {
        String sql = "SELECT order_id, user_id, order_date, total_amount, status\n" +
                "FROM orders\nWHERE status = 'PENDING'\nORDER BY order_date DESC\nLIMIT 20;";

        System.out.println("\n" + "=".repeat(70));
        System.out.println("CASE: Composite Index - Traditional WITHOUT Index");
        System.out.println("=".repeat(70));

        System.out.println("\nBusiness problem:");
        System.out.println("- Admin xem cac don hang PENDING moi nhat de xu ly.");

        System.out.println("\nSQL:");
        System.out.println(sql);

        // Drop composite indexes
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP INDEX IF EXISTS idx_orders_status_date");
            stmt.execute("DROP INDEX IF EXISTS idx_orders_pending");
            stmt.execute("ANALYZE orders");
            System.out.println("\n[Index dropped: idx_orders_status_date, idx_orders_pending]");
        }

        lastCompositeBefore = runExplainAnalyze(sql);

        System.out.println("\nEXPLAIN ANALYZE:");
        for (String line : lastCompositeBefore.outputLines) {
            System.out.println("  " + line);
        }

        System.out.printf("%nExecution time: %.1f ms%n", lastCompositeBefore.executionTimeMs);

        printPlanSummary(lastCompositeBefore, false, null);

        System.out.println("\nExpected issue:");
        System.out.println("- Traditional approach: WITHOUT composite index on (status, order_date DESC)");
        System.out.println("- status co nhieu dong, neu khong co index theo status + order_date");
        System.out.println("  thi database phai scan/filter/sort nhieu du lieu.");
        System.out.println("=".repeat(70));
    }

    /**
     * Menu 7: Composite Optimized — WITH composite index on orders(status, order_date)
     */
    public void runCompositeOptimized() throws Exception {
        String sql = "SELECT order_id, user_id, order_date, total_amount, status\n" +
                "FROM orders\nWHERE status = 'PENDING'\nORDER BY order_date DESC\nLIMIT 20;";

        System.out.println("\n" + "=".repeat(70));
        System.out.println("CASE: Composite Index - Optimized WITH Index");
        System.out.println("=".repeat(70));

        System.out.println("\nBusiness problem:");
        System.out.println("- Admin xem cac don hang PENDING moi nhat de xu ly.");

        // Create composite index
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            System.out.println("\nDang tao index idx_orders_status_date...");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_orders_status_date ON orders(status, order_date DESC)");
            stmt.execute("ANALYZE orders");
            System.out.println("[Index created: idx_orders_status_date]");
        }

        System.out.println("\nIndex created:");
        System.out.println("CREATE INDEX IF NOT EXISTS idx_orders_status_date");
        System.out.println("ON orders(status, order_date DESC);");

        System.out.println("\nSQL:");
        System.out.println(sql);

        ExplainResult afterResult = runExplainAnalyze(sql);
        lastCompositeAfter = afterResult;

        System.out.println("\nEXPLAIN ANALYZE:");
        for (String line : afterResult.outputLines) {
            System.out.println("  " + line);
        }

        System.out.printf("%nExecution time: %.1f ms%n", afterResult.executionTimeMs);

        String indexUsed = printPlanSummary(afterResult, true,
                "Composite index ho tro ca WHERE status va ORDER BY order_date DESC trong mot index scan.");

        printBeforeAfterComparison(lastCompositeBefore, afterResult, indexUsed);
        System.out.println("=".repeat(70));
    }

    /**
     * Menu 8: Covering Traditional — WITH normal composite index but WITHOUT covering index
     */
    public void runCoveringTraditional() throws Exception {
        String sql = "SELECT user_id, order_date, total_amount, status\n" +
                "FROM orders\nWHERE user_id = 42\nORDER BY order_date DESC\nLIMIT 20;";

        System.out.println("\n" + "=".repeat(70));
        System.out.println("CASE: Covering Index - Traditional WITHOUT Covering Index");
        System.out.println("=".repeat(70));

        System.out.println("\nBusiness problem:");
        System.out.println("- Man hinh lich su don hang chi can hien thi user_id, order_date,");
        System.out.println("  total_amount, status.");

        System.out.println("\nSQL:");
        System.out.println(sql);

        // Drop covering index, but ensure normal composite index exists
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP INDEX IF EXISTS idx_orders_user_date_covering");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_orders_user_date ON orders(user_id, order_date DESC)");
            stmt.execute("ANALYZE orders");
            System.out.println("\n[Index dropped: idx_orders_user_date_covering]");
            System.out.println("[Index kept: idx_orders_user_date (normal composite)]");
        }

        lastCoveringBefore = runExplainAnalyze(sql);

        System.out.println("\nEXPLAIN ANALYZE:");
        for (String line : lastCoveringBefore.outputLines) {
            System.out.println("  " + line);
        }

        System.out.printf("%nExecution time: %.1f ms%n", lastCoveringBefore.executionTimeMs);

        printPlanSummary(lastCoveringBefore, false, null);

        System.out.println("\nExpected plan:");
        System.out.println("- Traditional approach: WITH normal composite index but WITHOUT covering index");
        System.out.println("- Index Scan; may still access heap/table for total_amount and status");
        System.out.println("=".repeat(70));
    }

    /**
     * Menu 9: Covering Optimized — WITH covering index INCLUDE(total_amount, status)
     */
    public void runCoveringOptimized() throws Exception {
        String sql = "SELECT user_id, order_date, total_amount, status\n" +
                "FROM orders\nWHERE user_id = 42\nORDER BY order_date DESC\nLIMIT 20;";

        System.out.println("\n" + "=".repeat(70));
        System.out.println("CASE: Covering Index - Optimized WITH Covering Index");
        System.out.println("=".repeat(70));

        System.out.println("\nBusiness problem:");
        System.out.println("- Man hinh lich su don hang chi can hien thi user_id, order_date,");
        System.out.println("  total_amount, status.");

        // Create covering index + VACUUM
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            System.out.println("\nDang tao covering index idx_orders_user_date_covering...");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_orders_user_date_covering\n" +
                    "ON orders(user_id, order_date DESC)\nINCLUDE (total_amount, status)");
            stmt.execute("VACUUM ANALYZE orders");
            System.out.println("[Index created: idx_orders_user_date_covering]");
            System.out.println("[VACUUM ANALYZE completed]");
        }

        System.out.println("\nIndex created:");
        System.out.println("CREATE INDEX IF NOT EXISTS idx_orders_user_date_covering");
        System.out.println("ON orders(user_id, order_date DESC)");
        System.out.println("INCLUDE (total_amount, status);");

        System.out.println("\nSQL:");
        System.out.println(sql);

        ExplainResult afterResult = runExplainAnalyze(sql);
        lastCoveringAfter = afterResult;

        System.out.println("\nEXPLAIN ANALYZE:");
        for (String line : afterResult.outputLines) {
            System.out.println("  " + line);
        }

        System.out.printf("%nExecution time: %.1f ms%n", afterResult.executionTimeMs);

        String indexUsed = printPlanSummary(afterResult, true,
                "Covering index chua du cot query can. PostgreSQL co the dung Index Only Scan.\n" +
                "  Neu Heap Fetches = 0 thi chung minh khong can doc bang chinh.");

        // Check Heap Fetches
        boolean heapFetchesZero = false;
        for (String line : afterResult.outputLines) {
            if (line.contains("Heap Fetches:")) {
                System.out.println("\n  >> " + line.trim());
                if (line.contains("Heap Fetches: 0")) {
                    heapFetchesZero = true;
                }
            }
        }
        if (heapFetchesZero) {
            System.out.println("  >> Heap Fetches = 0: Index Only Scan thanh cong!");
        }

        printBeforeAfterComparison(lastCoveringBefore, afterResult, indexUsed);
        System.out.println("=".repeat(70));
    }

    /**
     * Menu 10: Run full indexing comparison report (4 -> 5 -> 6 -> 7 -> 8 -> 9)
     */
    public void runFullIndexingReport() throws Exception {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("  FULL INDEXING COMPARISON REPORT");
        System.out.println("=".repeat(70));

        // Run all 6 steps sequentially
        runBTreeTraditional();
        runBTreeOptimized();
        runCompositeTraditional();
        runCompositeOptimized();
        runCoveringTraditional();
        runCoveringOptimized();

        // Print summary table
        System.out.println("\n" + "=".repeat(70));
        System.out.println("  SUMMARY TABLE");
        System.out.println("=".repeat(70));
        System.out.println();
        System.out.printf("| %-10s | %-25s | %-20s | %-20s | %10s | %10s | %8s | %-25s |%n",
                "Index Type", "Business Problem", "Traditional Plan", "Optimized Plan",
                "Before ms", "After ms", "Speedup", "Index Used");
        System.out.println("|" + "-".repeat(12) + "|" + "-".repeat(27) + "|" + "-".repeat(22) + "|" +
                "-".repeat(22) + "|" + "-".repeat(12) + "|" + "-".repeat(12) + "|" + "-".repeat(10) + "|" + "-".repeat(27) + "|");

        printSummaryRow("B-Tree", "Order item lookup",
                lastBTreeBefore, lastBTreeAfter, "idx_order_items_order_id");
        printSummaryRow("Composite", "Pending orders",
                lastCompositeBefore, lastCompositeAfter, "idx_orders_status_date");
        printSummaryRow("Covering", "User order history",
                lastCoveringBefore, lastCoveringAfter, "idx_orders_user_date_covering");

        System.out.println("\n" + "=".repeat(70));
        System.out.println("  HOAN TAT FULL INDEXING COMPARISON REPORT");
        System.out.println("=".repeat(70));
    }

    private void printSummaryRow(String indexType, String problem,
                                 ExplainResult before, ExplainResult after,
                                 String indexName) {
        String beforePlan = extractScanType(before);
        String afterPlan = extractScanType(after);
        double beforeMs = before != null ? before.executionTimeMs : 0;
        double afterMs = after != null ? after.executionTimeMs : 0;
        String speedup = "N/A";
        if (beforeMs > 0 && afterMs > 0) {
            speedup = String.format("%.1fx", beforeMs / afterMs);
        }
        System.out.printf("| %-10s | %-25s | %-20s | %-20s | %10.1f | %10.1f | %8s | %-25s |%n",
                indexType, problem, beforePlan, afterPlan, beforeMs, afterMs, speedup, indexName);
    }

    /**
     * Menu 11: Pagination comparison (OFFSET vs KEYSET)
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
    // Business Case Indexing Demos (kept for runAllIndexingDemos compatibility)
    // ========================================================================

    public void runAllIndexingDemos() throws Exception {
        runBTreeTraditional();
        runBTreeOptimized();
        runCompositeTraditional();
        runCompositeOptimized();
        runCoveringTraditional();
        runCoveringOptimized();
        System.out.println("\n" + "=".repeat(70));
        System.out.println("  HOAN TAT TAT CA INDEXING DEMOS");
        System.out.println("=".repeat(70));
    }

    // ========================================================================
    // Pagination benchmarks
    // ========================================================================

    private double benchmarkOffsetPagination(int pageSize, long offset) throws Exception {
        String sql = "SELECT order_id, user_id, order_date, total_amount, status " +
                     "FROM orders ORDER BY order_id LIMIT ? OFFSET ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, pageSize);
            ps.setLong(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) { /* drain */ }
            }
        }

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

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, cursorId);
            ps.setInt(2, pageSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) { /* drain */ }
            }
        }

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

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_orders_user_date_covering " +
                    "ON orders(user_id, order_date DESC) INCLUDE (total_amount, status)");
            System.out.println("  [9/9] idx_orders_user_date_covering (covering)");

            stmt.execute("ANALYZE orders");
            stmt.execute("ANALYZE order_items");

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.printf("%n  => Tao index hoan tat trong %,d ms.%n", elapsed);

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

    // ========================================================================
    // EXPLAIN ANALYZE helper
    // ========================================================================

    static class ExplainResult {
        final List<String> outputLines;
        final double executionTimeMs;

        ExplainResult(List<String> outputLines, double executionTimeMs) {
            this.outputLines = outputLines;
            this.executionTimeMs = executionTimeMs;
        }
    }

    private ExplainResult runExplainAnalyze(String sql) {
        List<String> lines = new ArrayList<>();
        double execTime = 0.0;

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " + sql)) {
            while (rs.next()) {
                String line = rs.getString(1);
                lines.add(line);
                if (line.contains("Execution Time:")) {
                    String timeStr = line.substring(line.indexOf(":") + 1).trim();
                    timeStr = timeStr.replace(" ms", "").trim();
                    execTime = Double.parseDouble(timeStr);
                }
            }
        } catch (Exception e) {
            lines.add("Loi: " + e.getMessage());
        }

        return new ExplainResult(lines, execTime);
    }

    // ========================================================================
    // Output helpers
    // ========================================================================

    private String extractScanType(ExplainResult result) {
        if (result == null) return "N/A";
        for (String line : result.outputLines) {
            if (line.contains("Index Only Scan")) return "Index Only Scan";
            if (line.contains("Index Scan")) return "Index Scan";
            if (line.contains("Bitmap Index Scan")) return "Bitmap Index Scan";
            if (line.contains("Parallel Seq Scan")) return "Parallel Seq Scan";
            if (line.contains("Seq Scan")) return "Seq Scan";
        }
        return "N/A";
    }

    private String extractIndexUsed(ExplainResult result) {
        if (result == null) return "N/A";
        for (String line : result.outputLines) {
            if (line.contains("Index Only Scan using")) {
                int start = line.indexOf("using ") + 6;
                int end = line.indexOf(" on ", start);
                if (end > start) return line.substring(start, end);
            } else if (line.contains("Index Scan using")) {
                int start = line.indexOf("using ") + 6;
                int end = line.indexOf(" on ", start);
                if (end > start) return line.substring(start, end);
            }
        }
        return "N/A";
    }

    private String extractRowsRemoved(ExplainResult result) {
        if (result == null) return "N/A";
        for (String line : result.outputLines) {
            if (line.contains("Rows Removed by Filter")) {
                return line.trim();
            }
        }
        return "N/A";
    }

    private String extractBuffers(ExplainResult result) {
        if (result == null) return "N/A";
        for (String line : result.outputLines) {
            if (line.contains("Buffers:")) {
                return line.trim();
            }
        }
        return "N/A";
    }

    /**
     * Print plan summary for the output.
     * @return index used (for comparison display)
     */
    private String printPlanSummary(ExplainResult result, boolean isOptimized, String whyFaster) {
        String scanType = extractScanType(result);
        String indexUsed = extractIndexUsed(result);
        String rowsRemoved = extractRowsRemoved(result);
        String buffers = extractBuffers(result);

        System.out.println("\nPlan summary:");
        System.out.println("- Scan type: " + scanType);

        if (isOptimized) {
            if (!"N/A".equals(indexUsed)) {
                System.out.println("- Index used: " + indexUsed);
            }
            if (whyFaster != null) {
                System.out.println("- Why faster: " + whyFaster);
            }
        } else {
            if (!"N/A".equals(rowsRemoved)) {
                System.out.println("- " + rowsRemoved);
            }
        }

        if (!"N/A".equals(buffers)) {
            System.out.println("- " + buffers);
        }

        return indexUsed;
    }

    private void printBeforeAfterComparison(ExplainResult before, ExplainResult after,
                                            String indexUsed) {
        System.out.println("\n==================================================");
        System.out.println("Performance comparison:");
        System.out.println("==================================================");

        if (before != null) {
            System.out.printf("Before: %.1f ms%n", before.executionTimeMs);
            System.out.printf("After:  %.1f ms%n", after.executionTimeMs);

            if (before.executionTimeMs > 0 && after.executionTimeMs > 0) {
                double speedup = before.executionTimeMs / after.executionTimeMs;
                System.out.printf("Speedup: %.1fx%n", speedup);
            }

            String beforePlan = extractScanType(before);
            String afterPlan = extractScanType(after);
            System.out.println("\nBefore plan: " + beforePlan);
            System.out.println("After plan:  " + afterPlan);
        } else {
            System.out.printf("After: %.1f ms%n", after.executionTimeMs);
            System.out.println("\n[Chua co ket qua before. Vui long chay menu traditional truoc.]");
        }

        if (indexUsed != null && !"N/A".equals(indexUsed)) {
            System.out.println("Index used: " + indexUsed);
        }
    }
}
