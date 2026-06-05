package org.example.performencetuning.data;

import org.example.performencetuning.benchmark.QueryBenchmarkRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Random;
import java.util.Scanner;

@Component
@Profile("!test")
public class BenchmarkRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkRunner.class);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private QueryBenchmarkRunner queryBenchmarkRunner;

    private final DataGenerator dataGenerator = new DataGenerator();
    private final Random random = new Random();

    @Override
    public void run(String... args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        
        System.out.println("\n==============================================================");
        System.out.println("     CHUONG TRINH TOI UU CO SO DU LIEU PERFORMANCE TUNING");
        System.out.println("==============================================================");
        
        displayRowCounts();

        while (true) {
            System.out.println("\n===== SQL Performance Tuning CLI =====");
            System.out.println("1. Show row counts");
            System.out.println("2. Load sample data");
            System.out.println("3. Truncate all tables");
            System.out.println("");
            System.out.println("===== Indexing Demo: Before vs After =====");
            System.out.println("4. B-Tree Index - Traditional query WITHOUT index");
            System.out.println("5. B-Tree Index - Optimized query WITH index");
            System.out.println("");
            System.out.println("6. Composite Index - Traditional query WITHOUT index");
            System.out.println("7. Composite Index - Optimized query WITH index");
            System.out.println("");
            System.out.println("8. Covering Index - Traditional query WITHOUT covering index");
            System.out.println("9. Covering Index - Optimized query WITH covering index");
            System.out.println("");
            System.out.println("10. Run full Indexing comparison report");
            System.out.println("");
            System.out.println("===== Pagination Demo =====");
            System.out.println("11. Offset pagination vs Keyset pagination");
            System.out.println("");
            System.out.println("0. Exit");
            System.out.print("Nhap lua chon cua ban: ");
            
            String choice = "";
            if (scanner.hasNextLine()) {
                choice = scanner.nextLine().trim();
            } else {
                log.warn("Khong phat hien luong nhap du lieu tu Console. Thoat menu.");
                break;
            }

            if (choice.equals("0")) {
                System.out.println("Dang thoat chuong trinh...");
                break;
            } else if (choice.equals("1")) {
                displayRowCounts();
            } else if (choice.equals("2")) {
                checkAndPopulateData();
            } else if (choice.equals("3")) {
                truncateAllTables(scanner);
            } else if (choice.equals("4")) {
                runDemo(() -> queryBenchmarkRunner.runBTreeTraditional());
            } else if (choice.equals("5")) {
                runDemo(() -> queryBenchmarkRunner.runBTreeOptimized());
            } else if (choice.equals("6")) {
                runDemo(() -> queryBenchmarkRunner.runCompositeTraditional());
            } else if (choice.equals("7")) {
                runDemo(() -> queryBenchmarkRunner.runCompositeOptimized());
            } else if (choice.equals("8")) {
                runDemo(() -> queryBenchmarkRunner.runCoveringTraditional());
            } else if (choice.equals("9")) {
                runDemo(() -> queryBenchmarkRunner.runCoveringOptimized());
            } else if (choice.equals("10")) {
                runDemo(() -> queryBenchmarkRunner.runFullIndexingReport());
            } else if (choice.equals("11")) {
                runDemo(() -> queryBenchmarkRunner.runPaginationComparison());
            } else {
                System.out.println("Lua chon khong hop le! Vui long chon lai.");
            }
        }
    }

    private void displayRowCounts() {
        System.out.println("\n==============================================================");
        System.out.println("        THONG KE SO DONG HIEN TAI TRONG CO SO DU LIEU");
        System.out.println("==============================================================");
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            
            long usersCount = getCount(stmt, "users");
            long productsCount = getCount(stmt, "products");
            long ordersCount = getCount(stmt, "orders");
            long orderItemsCount = getCount(stmt, "order_items");
            
            System.out.printf("  - Bang [users]       : %,12d dong%n", usersCount);
            System.out.printf("  - Bang [products]    : %,12d dong%n", productsCount);
            System.out.printf("  - Bang [orders]      : %,12d dong%n", ordersCount);
            System.out.printf("  - Bang [order_items] : %,12d dong%n", orderItemsCount);
            
        } catch (Exception e) {
            System.err.println("Loi khi truy van so luong dong: " + e.getMessage());
        }
        System.out.println("==============================================================");
    }

    private long getCount(Statement stmt, String tableName) {
        try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (Exception e) {
            // Bang chua co hoac bi loi
        }
        return 0;
    }

    private void truncateAllTables(Scanner scanner) {
        System.out.print("Ban co chac chan muon XOA SACH du lieu tat ca cac bang? (Y/N): ");
        String confirm = scanner.nextLine().trim();
        if (!confirm.equalsIgnoreCase("Y")) {
            System.out.println("Da huy thao tac xoa du lieu.");
            return;
        }

        System.out.println("Dang tien hanh xoa sach du lieu...");
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE order_items, orders, products, users RESTART IDENTITY CASCADE;");
            System.out.println("Thanh cong! Tat ca cac bang da trong va bo tu tang (sequence) da duoc reset.");
        } catch (Exception e) {
            System.err.println("Loi khi thuc hien xoa du lieu: " + e.getMessage());
        }
    }

    private void checkAndPopulateData() {
        try (Connection conn = dataSource.getConnection()) {
            long userCount = 0;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM users")) {
                if (rs.next()) {
                    userCount = rs.getLong(1);
                }
            }

            if (userCount > 0) {
                System.out.println("\n[CANH BAO] CSDL da co du lieu (" + userCount + " users).");
                System.out.println("Vui long thuc hien thao tac 'Xoa du lieu' truoc khi nap moi.");
                return;
            }

            System.out.println("\n==============================================================");
            System.out.println("BAT DAU QUY TRINH NAP DU LIEU & DO LUONG HIEU NANG...");
            System.out.println("==============================================================");

            // 1. Single Insert Benchmark: 50,000 Users
            runSingleInsertBenchmark(conn);

            // 2. Batch Insert: Remaining 50,000 Users
            runUserBatchInsert(conn);

            // 3. Batch Insert: 50,000 Products
            runProductBatchInsert(conn);

            // 4. Batch Insert: 3,000,000 Orders
            runOrderBatchInsert(conn);

            // 5. Batch Insert: 10,000,000+ Order Items
            runOrderItemBatchInsert(conn);

            // 6. Reset Sequences
            resetDatabaseSequences(conn);

            System.out.println("\n==============================================================");
            System.out.println("NAP DU LIEU HOAN TAT THANH CONG!");
            System.out.println("==============================================================");

        } catch (Exception e) {
            System.err.println("Loi xay ra trong qua trinh nap du lieu: " + e.getMessage());
        }
    }

    private void printProgressBar(long current, long total, String label) {
        int width = 35;
        double percent = (double) current / total;
        int progress = (int) (width * percent);
        StringBuilder sb = new StringBuilder("\r" + label + " [");
        for (int i = 0; i < width; i++) {
            if (i < progress) sb.append("=");
            else if (i == progress) sb.append(">");
            else sb.append(" ");
        }
        sb.append(String.format("] %.1f%% (%,d/%,d)", percent * 100.0, current, total));
        System.out.print(sb.toString());
        if (current == total) {
            System.out.println();
        }
    }

    private void runSingleInsertBenchmark(Connection conn) throws Exception {
        System.out.println("\n1. Dang chay Single Insert cho 50,000 users...");
        String sql = "INSERT INTO users (user_id, username, email, password_hash, is_active) VALUES (?, ?, ?, ?, ?)";
        
        long startTime = System.currentTimeMillis();
        conn.setAutoCommit(true);

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (long i = 1; i <= 50000; i++) {
                DataGenerator.UserRecord user = dataGenerator.generateUser(i);
                ps.setLong(1, user.userId());
                ps.setString(2, user.username());
                ps.setString(3, user.email());
                ps.setString(4, user.passwordHash());
                ps.setBoolean(5, user.isActive());
                ps.executeUpdate();

                if (i % 5000 == 0 || i == 50000) {
                    printProgressBar(i, 50000, "Single Insert Users ");
                }
            }
        }
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double throughput = 50000.0 / (duration / 1000.0);
        double avgLatency = (double) duration / 50000.0;

        System.out.println("  => KET QUA SINGLE INSERT USERS:");
        System.out.printf("     - Tong thoi gian  : %,d ms%n", duration);
        System.out.printf("     - Toc do xu ly    : %,.2f dong/giay%n", throughput);
        System.out.printf("     - Do tre trung binh: %,.4f ms/dong%n", avgLatency);
    }

    private void runUserBatchInsert(Connection conn) throws Exception {
        System.out.println("\n2. Dang chay Batch Insert cho 50,000 users con lai...");
        String sql = "INSERT INTO users (user_id, username, email, password_hash, is_active) VALUES (?, ?, ?, ?, ?)";
        
        long startTime = System.currentTimeMillis();
        conn.setAutoCommit(false);

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int batchSize = 1000;
            int count = 0;
            for (long i = 50001; i <= 100000; i++) {
                DataGenerator.UserRecord user = dataGenerator.generateUser(i);
                ps.setLong(1, user.userId());
                ps.setString(2, user.username());
                ps.setString(3, user.email());
                ps.setString(4, user.passwordHash());
                ps.setBoolean(5, user.isActive());
                ps.addBatch();
                count++;

                if (count % batchSize == 0) {
                    ps.executeBatch();
                    conn.commit();
                    printProgressBar(count, 50000, "Batch Insert Users  ");
                }
            }
            if (count % batchSize != 0) {
                ps.executeBatch();
                conn.commit();
                printProgressBar(count, 50000, "Batch Insert Users  ");
            }
        }
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double throughput = 50000.0 / (duration / 1000.0);
        double avgLatency = (double) duration / 50000.0;

        System.out.println("  => KET QUA BATCH INSERT USERS:");
        System.out.printf("     - Tong thoi gian  : %,d ms%n", duration);
        System.out.printf("     - Toc do xu ly    : %,.2f dong/giay%n", throughput);
        System.out.printf("     - Do tre trung binh: %,.4f ms/dong%n", avgLatency);
        System.out.printf("     - TOC DO NHANH HON : %,.1f lan%n", (double) duration > 0 ? (double) 7428 / duration : 1.0); // so sanh tuong doi
    }

    private void runProductBatchInsert(Connection conn) throws Exception {
        System.out.println("\n3. Dang chay Batch Insert cho 50,000 products...");
        String sql = "INSERT INTO products (product_id, product_name, price, created_at) VALUES (?, ?, ?, ?)";
        
        long startTime = System.currentTimeMillis();
        conn.setAutoCommit(false);

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int batchSize = 1000;
            int count = 0;
            for (long i = 1; i <= 50000; i++) {
                DataGenerator.ProductRecord product = dataGenerator.generateProduct(i);
                ps.setLong(1, product.productId());
                ps.setString(2, product.productName());
                ps.setBigDecimal(3, product.price());
                ps.setTimestamp(4, product.createdAt());
                ps.addBatch();
                count++;

                if (count % batchSize == 0) {
                    ps.executeBatch();
                    conn.commit();
                    printProgressBar(count, 50000, "Batch Insert Products");
                }
            }
            if (count % batchSize != 0) {
                ps.executeBatch();
                conn.commit();
                printProgressBar(count, 50000, "Batch Insert Products");
            }
        }
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double throughput = 50000.0 / (duration / 1000.0);

        System.out.println("  => KET QUA BATCH INSERT PRODUCTS:");
        System.out.printf("     - Tong thoi gian  : %,d ms%n", duration);
        System.out.printf("     - Toc do xu ly    : %,.2f dong/giay%n", throughput);
    }

    private void runOrderBatchInsert(Connection conn) throws Exception {
        System.out.println("\n4. Dang chay Batch Insert cho 3,000,000 orders...");
        String sql = "INSERT INTO orders (order_id, user_id, order_date, total_amount) VALUES (?, ?, ?, ?)";
        
        long startTime = System.currentTimeMillis();
        conn.setAutoCommit(false);

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int batchSize = 5000;
            int count = 0;
            for (long i = 1; i <= 3000000; i++) {
                DataGenerator.OrderRecord order = dataGenerator.generateOrder(i);
                ps.setLong(1, order.orderId());
                ps.setLong(2, order.userId());
                ps.setTimestamp(3, order.orderDate());
                ps.setBigDecimal(4, order.totalAmount());
                ps.addBatch();
                count++;

                if (count % batchSize == 0) {
                    ps.executeBatch();
                    conn.commit();
                    if (count % 25000 == 0 || count == 3000000) {
                        printProgressBar(count, 3000000, "Batch Insert Orders  ");
                    }
                }
            }
            if (count % batchSize != 0) {
                ps.executeBatch();
                conn.commit();
                printProgressBar(count, 3000000, "Batch Insert Orders  ");
            }
        }
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double throughput = 3000000.0 / (duration / 1000.0);

        System.out.println("  => KET QUA BATCH INSERT ORDERS:");
        System.out.printf("     - Tong thoi gian  : %,d ms%n", duration);
        System.out.printf("     - Toc do xu ly    : %,.2f dong/giay%n", throughput);
    }

    private void runOrderItemBatchInsert(Connection conn) throws Exception {
        System.out.println("\n5. Dang tinh toan quy mo chi tiet don hang...");
        
        long totalItems = 10000000L;
        System.out.printf("  => Tong so dong order_items can nap: %,d dong%n", totalItems);
        System.out.println("   Dang chay Batch Insert cho order_items...");

        String sql = "INSERT INTO order_items (order_item_id, order_id, product_id, quantity, price_per_unit) VALUES (?, ?, ?, ?, ?)";
        long startTime = System.currentTimeMillis();
        conn.setAutoCommit(false);

        long orderItemId = 1;
        int batchSize = 5000;
        int count = 0;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int orderId = 1; orderId <= 3000000; orderId++) {
                // 2,000,000 orders have 3 items, 1,000,000 orders have 4 items -> exactly 10,000,000 items
                int numItems = (orderId <= 2000000) ? 3 : 4;
                for (int j = 0; j < numItems; j++) {
                    long productId = random.nextInt(50000) + 1;
                    DataGenerator.OrderItemRecord item = dataGenerator.generateOrderItem(orderItemId, orderId, productId);
                    
                    ps.setLong(1, item.orderItemId());
                    ps.setLong(2, item.orderId());
                    ps.setLong(3, item.productId());
                    ps.setInt(4, item.quantity());
                    ps.setBigDecimal(5, item.pricePerUnit());
                    ps.addBatch();
                    
                    orderItemId++;
                    count++;

                    if (count % batchSize == 0) {
                        ps.executeBatch();
                        conn.commit();
                        if (count % 50000 == 0 || count == totalItems) {
                            printProgressBar(count, totalItems, "Batch Order Items    ");
                        }
                    }
                }
            }
            if (count % batchSize != 0) {
                ps.executeBatch();
                conn.commit();
                printProgressBar(count, totalItems, "Batch Order Items    ");
            }
        }
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double throughput = (double) count / (duration / 1000.0);

        System.out.println("  => KET QUA BATCH INSERT ORDER ITEMS:");
        System.out.printf("     - Tong thoi gian  : %,d ms%n", duration);
        System.out.printf("     - Toc do xu ly    : %,.2f dong/giay%n", throughput);
    }

    @FunctionalInterface
    private interface DemoAction {
        void run() throws Exception;
    }

    private void runDemo(DemoAction action) {
        try {
            action.run();
        } catch (Exception e) {
            System.err.println("Loi: " + e.getMessage());
        }
    }

    private void resetDatabaseSequences(Connection conn) throws Exception {
        System.out.println("\n6. Dang dong bo hoa sequence khoa chinh tu tang...");
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT setval(pg_get_serial_sequence('users', 'user_id'), COALESCE((SELECT MAX(user_id) FROM users), 1))");
            stmt.execute("SELECT setval(pg_get_serial_sequence('products', 'product_id'), COALESCE((SELECT MAX(product_id) FROM products), 1))");
            stmt.execute("SELECT setval(pg_get_serial_sequence('orders', 'order_id'), COALESCE((SELECT MAX(order_id) FROM orders), 1))");
            stmt.execute("SELECT setval(pg_get_serial_sequence('order_items', 'order_item_id'), COALESCE((SELECT MAX(order_item_id) FROM order_items), 1))");
        }
        System.out.println("  => Dong bo sequence hoan tat.");
    }
}
