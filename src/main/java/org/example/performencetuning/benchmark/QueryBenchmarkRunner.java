package org.example.performencetuning.benchmark;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        System.out.println("\nEXPLAIN ANALYZE RAW OUTPUT:");
        for (String line : lastBTreeBefore.outputLines) {
            System.out.println("  " + line);
        }

        ExplainSummary summary = parseExplainOutput(lastBTreeBefore);
        System.out.println("\nParsed Execution Summary:");
        printExecutionSummary(summary);
        System.out.println("\nEXPLAIN Meaning:");
        printExplainMeaning(summary);
        printPlanInterpretation(summary, true, false);

        printPlanSummary(lastBTreeBefore, false, null);

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

        System.out.println("\nEXPLAIN ANALYZE RAW OUTPUT:");
        for (String line : afterResult.outputLines) {
            System.out.println("  " + line);
        }

        ExplainSummary summary = parseExplainOutput(afterResult);
        System.out.println("\nParsed Execution Summary:");
        printExecutionSummary(summary);
        System.out.println("\nEXPLAIN Meaning:");
        printExplainMeaning(summary);
        printPlanInterpretation(summary, false, false);

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

        System.out.println("\nEXPLAIN ANALYZE RAW OUTPUT:");
        for (String line : lastCompositeBefore.outputLines) {
            System.out.println("  " + line);
        }

        ExplainSummary summary = parseExplainOutput(lastCompositeBefore);
        System.out.println("\nParsed Execution Summary:");
        printExecutionSummary(summary);
        System.out.println("\nEXPLAIN Meaning:");
        printExplainMeaning(summary);
        printPlanInterpretation(summary, true, false);

        printPlanSummary(lastCompositeBefore, false, null);

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

        System.out.println("\nEXPLAIN ANALYZE RAW OUTPUT:");
        for (String line : afterResult.outputLines) {
            System.out.println("  " + line);
        }

        ExplainSummary summary = parseExplainOutput(afterResult);
        System.out.println("\nParsed Execution Summary:");
        printExecutionSummary(summary);
        System.out.println("\nEXPLAIN Meaning:");
        printExplainMeaning(summary);
        printPlanInterpretation(summary, false, false);

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

        System.out.println("\nEXPLAIN ANALYZE RAW OUTPUT:");
        for (String line : lastCoveringBefore.outputLines) {
            System.out.println("  " + line);
        }

        ExplainSummary summary = parseExplainOutput(lastCoveringBefore);
        System.out.println("\nParsed Execution Summary:");
        printExecutionSummary(summary);
        System.out.println("\nEXPLAIN Meaning:");
        printExplainMeaning(summary);
        printPlanInterpretation(summary, true, false);

        printPlanSummary(lastCoveringBefore, false, null);

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

        System.out.println("\nEXPLAIN ANALYZE RAW OUTPUT:");
        for (String line : afterResult.outputLines) {
            System.out.println("  " + line);
        }

        ExplainSummary summary = parseExplainOutput(afterResult);
        System.out.println("\nParsed Execution Summary:");
        printExecutionSummary(summary);
        System.out.println("\nEXPLAIN Meaning:");
        printExplainMeaning(summary);
        printPlanInterpretation(summary, false, true);

        String indexUsed = printPlanSummary(afterResult, true,
                "Covering index contains all columns the query needs. PostgreSQL can use\n" +
                "  Index Only Scan. If Heap Fetches = 0, no table access is needed.");

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

    /**
     * Parsed fields from EXPLAIN ANALYZE output.
     */
    static class ExplainSummary {
        String mainPlanNode = "N/A";
        String scanType = "N/A";
        String estimatedCost = "N/A";
        String estimatedRows = "N/A";
        String estimatedWidth = "N/A";
        String actualStartupTime = "N/A";
        String actualTotalTime = "N/A";
        String actualRows = "N/A";
        String rowsRemovedByFilter = "N/A";
        String buffers = "N/A";
        double executionTimeMs;
        String indexUsed = "N/A";
        String heapFetches = "N/A";
        String workersPlanned = "N/A";
        String workersLaunched = "N/A";
        String indexCond = "N/A";
        String filter = "N/A";
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

    // ========================================================================
    // EXPLAIN parser and formatted output
    // ========================================================================

    /**
     * Parse EXPLAIN ANALYZE output lines into a structured ExplainSummary.
     */
    private ExplainSummary parseExplainOutput(ExplainResult result) {
        ExplainSummary s = new ExplainSummary();
        if (result == null) return s;

        s.executionTimeMs = result.executionTimeMs;
        s.scanType = extractScanType(result);
        s.indexUsed = extractIndexUsed(result);

        // Regex patterns for parsing EXPLAIN lines
        Pattern costRowsWidthPattern = Pattern.compile(
                "cost=([\\d.]+)\\.\\.([\\d.]+)\\s+rows=(\\d+)\\s+width=(\\d+)");
        Pattern actualPattern = Pattern.compile(
                "actual time=([\\d.]+)\\.\\.([\\d.]+)\\s+rows=(\\d+)");

        for (String line : result.outputLines) {
            String trimmed = line.trim();

            // Main plan node: first line with cost/rows info (not starting with ->)
            if (!trimmed.startsWith("->") && trimmed.contains("cost=")
                    && "N/A".equals(s.mainPlanNode)) {
                // Extract node name (before the parenthesis)
                int parenIdx = trimmed.indexOf('(');
                if (parenIdx > 0) {
                    s.mainPlanNode = trimmed.substring(0, parenIdx).trim();
                }

                // Extract cost, rows, width
                Matcher m = costRowsWidthPattern.matcher(trimmed);
                if (m.find()) {
                    s.estimatedCost = m.group(1) + ".." + m.group(2);
                    s.estimatedRows = m.group(3);
                    s.estimatedWidth = m.group(4);
                }

                // Extract actual time and rows
                Matcher am = actualPattern.matcher(trimmed);
                if (am.find()) {
                    s.actualStartupTime = am.group(1);
                    s.actualTotalTime = am.group(2);
                    s.actualRows = am.group(3);
                }
            }

            // Workers
            if (trimmed.startsWith("Workers Planned:")) {
                s.workersPlanned = trimmed.substring(trimmed.indexOf(':') + 1).trim();
            }
            if (trimmed.startsWith("Workers Launched:")) {
                s.workersLaunched = trimmed.substring(trimmed.indexOf(':') + 1).trim();
            }

            // Rows Removed by Filter
            if (trimmed.contains("Rows Removed by Filter:")) {
                int idx = trimmed.indexOf("Rows Removed by Filter:");
                String val = trimmed.substring(idx + "Rows Removed by Filter:".length()).trim();
                s.rowsRemovedByFilter = formatNumber(val);
            }

            // Buffers (first one found, skip Planning buffers)
            if (trimmed.startsWith("Buffers:") && "N/A".equals(s.buffers)) {
                s.buffers = trimmed.substring("Buffers:".length()).trim();
            }

            // Heap Fetches
            if (trimmed.contains("Heap Fetches:")) {
                int idx = trimmed.indexOf("Heap Fetches:");
                s.heapFetches = trimmed.substring(idx + "Heap Fetches:".length()).trim();
            }

            // Index Cond
            if (trimmed.startsWith("Index Cond:") && "N/A".equals(s.indexCond)) {
                s.indexCond = trimmed.substring("Index Cond:".length()).trim();
            }

            // Filter (but not "Rows Removed by Filter")
            if (trimmed.startsWith("Filter:") && !trimmed.contains("Rows Removed")) {
                s.filter = trimmed.substring("Filter:".length()).trim();
            }
        }

        return s;
    }

    /**
     * Format a number string with commas (e.g. "3333332" -> "3,333,332").
     */
    private String formatNumber(String numStr) {
        try {
            long num = Long.parseLong(numStr.trim());
            return String.format("%,d", num);
        } catch (NumberFormatException e) {
            return numStr;
        }
    }

    /**
     * Print an ASCII table with auto-calculated column widths.
     * First row in `rows` is treated as the header.
     */
    private void printTable(String title, List<String[]> rows) {
        if (rows.isEmpty()) return;

        int numCols = rows.get(0).length;
        int[] colWidths = new int[numCols];

        // Calculate max width per column
        for (String[] row : rows) {
            for (int i = 0; i < numCols && i < row.length; i++) {
                colWidths[i] = Math.max(colWidths[i], row[i].length());
            }
        }
        // Add padding
        for (int i = 0; i < numCols; i++) {
            colWidths[i] += 2; // 1 space padding on each side
        }

        if (title != null && !title.isEmpty()) {
            System.out.println(title);
        }

        // Top border
        printTableBorder('┌', '┐', '┬', colWidths);

        // Header row (first row)
        printTableRow(rows.get(0), colWidths);

        // Header separator
        printTableBorder('├', '┤', '┼', colWidths);

        // Data rows
        for (int i = 1; i < rows.size(); i++) {
            printTableRow(rows.get(i), colWidths);
        }

        // Bottom border
        printTableBorder('└', '┘', '┴', colWidths);
    }

    private void printTableBorder(char left, char right, char mid, int[] colWidths) {
        StringBuilder sb = new StringBuilder();
        sb.append(left);
        for (int i = 0; i < colWidths.length; i++) {
            sb.append("─".repeat(colWidths[i]));
            sb.append(i < colWidths.length - 1 ? mid : right);
        }
        System.out.println(sb);
    }

    private void printTableRow(String[] cols, int[] colWidths) {
        StringBuilder sb = new StringBuilder();
        sb.append('│');
        for (int i = 0; i < colWidths.length; i++) {
            String val = i < cols.length ? cols[i] : "";
            sb.append(' ');
            sb.append(val);
            int padding = colWidths[i] - val.length() - 1;
            if (padding > 0) sb.append(" ".repeat(padding));
            sb.append('│');
        }
        System.out.println(sb);
    }

    /**
     * Print the parsed execution summary as an ASCII table.
     */
    private void printExecutionSummary(ExplainSummary s) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Metric", "Value"});
        rows.add(new String[]{"Main plan node", s.mainPlanNode});
        rows.add(new String[]{"Scan type", s.scanType});

        if (!"N/A".equals(s.estimatedCost)) {
            rows.add(new String[]{"Estimated cost", s.estimatedCost});
        }
        if (!"N/A".equals(s.estimatedRows)) {
            rows.add(new String[]{"Estimated rows", s.estimatedRows});
        }
        if (!"N/A".equals(s.estimatedWidth)) {
            rows.add(new String[]{"Estimated width (bytes)", s.estimatedWidth});
        }
        if (!"N/A".equals(s.actualStartupTime) && !"N/A".equals(s.actualTotalTime)) {
            rows.add(new String[]{"Actual time (ms)", s.actualStartupTime + " .. " + s.actualTotalTime});
        }
        if (!"N/A".equals(s.actualRows)) {
            rows.add(new String[]{"Actual rows returned", s.actualRows});
        }
        if (!"N/A".equals(s.rowsRemovedByFilter)) {
            rows.add(new String[]{"Rows removed by filter", s.rowsRemovedByFilter});
        }
        if (!"N/A".equals(s.buffers)) {
            rows.add(new String[]{"Buffers", s.buffers});
        }
        rows.add(new String[]{"Execution time (ms)", String.format("%.1f", s.executionTimeMs)});
        if (!"N/A".equals(s.indexUsed)) {
            rows.add(new String[]{"Index used", s.indexUsed});
        }
        if (!"N/A".equals(s.heapFetches)) {
            rows.add(new String[]{"Heap Fetches", s.heapFetches});
        }
        if (!"N/A".equals(s.workersPlanned)) {
            rows.add(new String[]{"Workers planned", s.workersPlanned});
        }
        if (!"N/A".equals(s.workersLaunched)) {
            rows.add(new String[]{"Workers launched", s.workersLaunched});
        }
        if (!"N/A".equals(s.indexCond)) {
            rows.add(new String[]{"Index Cond", s.indexCond});
        }
        if (!"N/A".equals(s.filter)) {
            rows.add(new String[]{"Filter", s.filter});
        }

        printTable("", rows);
    }

    /**
     * Print a table explaining what each EXPLAIN parameter means.
     */
    private void printExplainMeaning(ExplainSummary s) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Parameter", "Type", "Meaning"});

        if (!"N/A".equals(s.estimatedCost)) {
            rows.add(new String[]{
                    "cost=" + s.estimatedCost,
                    "Estimate",
                    "Planner estimates this query costs ~" + s.estimatedCost.split("\\.\\.")[1] + " cost units"
            });
        }
        if (!"N/A".equals(s.estimatedRows)) {
            rows.add(new String[]{
                    "rows=" + s.estimatedRows,
                    "Estimate",
                    "Planner expected " + s.estimatedRows + " rows to be returned"
            });
        }
        if (!"N/A".equals(s.estimatedWidth)) {
            rows.add(new String[]{
                    "width=" + s.estimatedWidth,
                    "Estimate",
                    "Each row is estimated at " + s.estimatedWidth + " bytes wide"
            });
        }
        if (!"N/A".equals(s.actualStartupTime) && !"N/A".equals(s.actualTotalTime)) {
            rows.add(new String[]{
                    "actual time=" + s.actualStartupTime + ".." + s.actualTotalTime,
                    "Actual",
                    "First row at " + s.actualStartupTime + "ms, finished at " + s.actualTotalTime + "ms"
            });
        }
        if (!"N/A".equals(s.actualRows)) {
            rows.add(new String[]{
                    "rows=" + s.actualRows + " (actual)",
                    "Actual",
                    s.actualRows + " rows actually matched the condition"
            });
        }
        if (!"N/A".equals(s.rowsRemovedByFilter)) {
            rows.add(new String[]{
                    "Rows Removed by Filter",
                    "Actual",
                    s.rowsRemovedByFilter + " rows read but discarded (not matching WHERE)"
            });
        }
        if (!"N/A".equals(s.buffers)) {
            rows.add(new String[]{
                    "Buffers",
                    "Actual",
                    "Pages read from memory (hit) and disk (read)"
            });
        }
        if (!"N/A".equals(s.heapFetches)) {
            rows.add(new String[]{
                    "Heap Fetches=" + s.heapFetches,
                    "Actual",
                    "Table page accesses during Index Only Scan. 0 = all data from index"
            });
        }
        if (!"N/A".equals(s.workersPlanned)) {
            rows.add(new String[]{
                    "Workers",
                    "Plan",
                    "Parallel workers: " + s.workersPlanned + " planned, " + s.workersLaunched + " launched"
            });
        }

        printTable("", rows);
    }

    /**
     * Print a human-readable interpretation of the execution plan.
     */
    private void printPlanInterpretation(ExplainSummary s, boolean isTraditional, boolean isCovering) {
        System.out.println("\nPlan Interpretation:");

        if (isTraditional) {
            printTraditionalInterpretation(s);
        } else if (isCovering) {
            printCoveringInterpretation(s);
        } else {
            printOptimizedInterpretation(s);
        }
    }

    private void printTraditionalInterpretation(ExplainSummary s) {
        String scan = s.scanType;
        System.out.println("- PostgreSQL used " + scan + ", which reads "
                + ("Parallel Seq Scan".equals(scan) ? "the table in parallel across multiple workers." : "the entire table sequentially."));

        if (!"N/A".equals(s.actualRows)) {
            System.out.println("- The query returned only " + s.actualRows + " row(s).");
        }
        if (!"N/A".equals(s.rowsRemovedByFilter)) {
            System.out.println("- However, " + s.rowsRemovedByFilter
                    + " rows were read and discarded by the filter — this is wasted I/O.");
        }
        if (!"N/A".equals(s.buffers)) {
            System.out.println("- Buffer usage (" + s.buffers + ") indicates heavy disk/memory reads.");
        }
        System.out.println("- This is why an index is needed — to avoid scanning the entire table.");
    }

    private void printOptimizedInterpretation(ExplainSummary s) {
        if (!"N/A".equals(s.indexUsed)) {
            System.out.println("- PostgreSQL used " + s.scanType + " using " + s.indexUsed + ".");
        } else {
            System.out.println("- PostgreSQL used " + s.scanType + ".");
        }
        System.out.println("- The database jumped directly to the matching rows using the index.");

        if ("N/A".equals(s.rowsRemovedByFilter) || "0".equals(s.rowsRemovedByFilter.replace(",", ""))) {
            System.out.println("- Rows removed by filter is zero or absent — no wasted reads.");
        } else {
            System.out.println("- Rows removed by filter: " + s.rowsRemovedByFilter + " (much lower than without index).");
        }
        if (!"N/A".equals(s.buffers)) {
            System.out.println("- Buffer usage (" + s.buffers + ") is much lower than the traditional query.");
        }
        System.out.println("- This proves the index is effective for this query pattern.");
    }

    private void printCoveringInterpretation(ExplainSummary s) {
        System.out.println("- PostgreSQL used " + s.scanType + " using " + s.indexUsed + ".");
        System.out.println("- The database read all needed columns directly from the index.");

        if (!"N/A".equals(s.heapFetches)) {
            System.out.println("- Heap Fetches = " + s.heapFetches + " — "
                    + ("0".equals(s.heapFetches)
                    ? "no table access needed. All data was in the index."
                    : "some table pages were still accessed (visibility map may need VACUUM)."));
        }
        if (!"N/A".equals(s.buffers)) {
            System.out.println("- Buffer usage (" + s.buffers + ") is lower than a regular Index Scan.");
        }
        System.out.println("- Covering index avoids random I/O to the heap for extra columns.");
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
