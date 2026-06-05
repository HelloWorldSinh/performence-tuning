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
        System.out.println("CASE: B-Tree Index — Traditional WITHOUT Index");
        System.out.println("=".repeat(70));

        System.out.println("\nBusiness problem:");
        System.out.println("- The system needs to retrieve all items for a specific order.");
        System.out.println("- When a customer opens order details, the system must fetch");
        System.out.println("  all products in that order.");

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

        printPlanSummary(lastBTreeBefore, false, null);

        System.out.println("\nExpected issue:");
        System.out.println("- Without an index on order_id, PostgreSQL must scan all 10M rows");
        System.out.println("  in order_items (Parallel Seq Scan) and filter out non-matching rows.");
        System.out.println("=".repeat(70));
    }

    /**
     * Menu 5: B-Tree Optimized — WITH index on order_items(order_id)
     */
    public void runBTreeOptimized() throws Exception {
        String sql = "SELECT order_item_id, order_id, product_id, quantity, price_per_unit\n" +
                "FROM order_items\nWHERE order_id = 100;";

        System.out.println("\n" + "=".repeat(70));
        System.out.println("CASE: B-Tree Index — Optimized WITH Index");
        System.out.println("=".repeat(70));

        System.out.println("\nBusiness problem:");
        System.out.println("- The system needs to retrieve all items for a specific order.");

        // Create B-Tree index
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            System.out.println("\nCreating index idx_order_items_order_id...");
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

        String indexUsed = printPlanSummary(afterResult, true,
                "B-Tree index on order_id allows direct lookup of items belonging to an order.");

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
        System.out.println("CASE: Composite Index — Traditional WITHOUT Index");
        System.out.println("=".repeat(70));

        System.out.println("\nBusiness problem:");
        System.out.println("- Admin wants to view the most recent PENDING orders to process.");

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
        System.out.println("- Without a composite index on (status, order_date DESC),");
        System.out.println("  the database must scan, filter, and sort many rows.");
        System.out.println("=".repeat(70));
    }

    /**
     * Menu 7: Composite Optimized — WITH composite index on orders(status, order_date)
     */
    public void runCompositeOptimized() throws Exception {
        String sql = "SELECT order_id, user_id, order_date, total_amount, status\n" +
                "FROM orders\nWHERE status = 'PENDING'\nORDER BY order_date DESC\nLIMIT 20;";

        System.out.println("\n" + "=".repeat(70));
        System.out.println("CASE: Composite Index — Optimized WITH Index");
        System.out.println("=".repeat(70));

        System.out.println("\nBusiness problem:");
        System.out.println("- Admin wants to view the most recent PENDING orders to process.");

        // Create composite index
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            System.out.println("\nCreating index idx_orders_status_date...");
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
                "Composite index supports both WHERE status and ORDER BY order_date DESC in a single index scan.");

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
        System.out.println("CASE: Covering Index — Traditional WITHOUT Covering Index");
        System.out.println("=".repeat(70));

        System.out.println("\nBusiness problem:");
        System.out.println("- The order history screen only needs to display user_id,");
        System.out.println("  order_date, total_amount, and status.");

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
        System.out.println("- With a normal composite index, PostgreSQL uses Index Scan");
        System.out.println("  but still needs to access the heap/table for total_amount and status.");
        System.out.println("=".repeat(70));
    }

    /**
     * Menu 9: Covering Optimized — WITH covering index INCLUDE(total_amount, status)
     */
    public void runCoveringOptimized() throws Exception {
        String sql = "SELECT user_id, order_date, total_amount, status\n" +
                "FROM orders\nWHERE user_id = 42\nORDER BY order_date DESC\nLIMIT 20;";

        System.out.println("\n" + "=".repeat(70));
        System.out.println("CASE: Covering Index — Optimized WITH Covering Index");
        System.out.println("=".repeat(70));

        System.out.println("\nBusiness problem:");
        System.out.println("- The order history screen only needs to display user_id,");
        System.out.println("  order_date, total_amount, and status.");

        // Create covering index + VACUUM
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            System.out.println("\nCreating covering index idx_orders_user_date_covering...");
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
                "Covering index contains all columns the query needs. PostgreSQL can use\n" +
                "  Index Only Scan. If Heap Fetches = 0, no table access is needed.");

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
            System.out.println("  >> Heap Fetches = 0: Index Only Scan succeeded!");
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
        System.out.println("  FULL INDEXING COMPARISON REPORT COMPLETED");
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
     * Run all indexing demos sequentially.
     */
    public void runAllIndexingDemos() throws Exception {
        runBTreeTraditional();
        runBTreeOptimized();
        runCompositeTraditional();
        runCompositeOptimized();
        runCoveringTraditional();
        runCoveringOptimized();
        System.out.println("\n" + "=".repeat(70));
        System.out.println("  ALL INDEXING DEMOS COMPLETED");
        System.out.println("=".repeat(70));
    }

    // ========================================================================
    // Index management
    // ========================================================================

    private void createAllIndexes() {
        System.out.println("\nCreating all indexes...");
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
            System.out.printf("%n  => Index creation completed in %,d ms.%n", elapsed);

            System.out.println("\n  Index sizes:");
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT indexname, pg_size_pretty(pg_relation_size(indexrelid)) " +
                    "FROM pg_stat_user_indexes WHERE schemaname = 'public' " +
                    "AND indexname LIKE 'idx_%' ORDER BY indexname")) {
                while (rs.next()) {
                    System.out.printf("    %-35s %s%n", rs.getString(1), rs.getString(2));
                }
            }
        } catch (Exception e) {
            System.err.println("  Error creating indexes: " + e.getMessage());
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
            lines.add("Error: " + e.getMessage());
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
            System.out.println("\n[No baseline result found. Please run the traditional demo first.]");
        }

        if (indexUsed != null && !"N/A".equals(indexUsed)) {
            System.out.println("Index used: " + indexUsed);
        }
    }
}
