package org.example.performencetuning.data;

import net.datafaker.Faker;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Random;

public class DataGenerator {

    private final Faker faker = new Faker();
    private final Random random = new Random();

    public UserRecord generateUser(long userId) {
        String username = "user_" + userId;
        String email = username + "@example.com";
        // Static hash to simulate bcrypt hashes without the overhead of computing bcrypt for 100k rows
        String passwordHash = "$2a$10$8.K3y7K5G.G6/T3v9O5a1.gE4C/Wf3Q8m6D2a5R1Y2Z3b4c5d6e7f";
        boolean isActive = random.nextDouble() < 0.95; // 95% active
        return new UserRecord(userId, username, email, passwordHash, isActive);
    }

    public ProductRecord generateProduct(long productId) {
        String productName = faker.commerce().productName() + " " + productId;
        BigDecimal price = BigDecimal.valueOf(10 + random.nextDouble() * 990).setScale(2, RoundingMode.HALF_UP);
        // Random date in the last 2 years
        Instant lastTwoYears = Instant.now().minus(730, ChronoUnit.DAYS);
        long randomSec = random.nextLong() % (730L * 24 * 60 * 60);
        if (randomSec < 0) randomSec = -randomSec;
        Instant createdAt = lastTwoYears.plusSeconds(randomSec);
        return new ProductRecord(productId, productName, price, Timestamp.from(createdAt));
    }

    public OrderRecord generateOrder(long orderId) {
        long userId = random.nextInt(100000) + 1;
        BigDecimal totalAmount = BigDecimal.valueOf(10 + random.nextDouble() * 4990).setScale(2, RoundingMode.HALF_UP);
        Instant lastTwoYears = Instant.now().minus(730, ChronoUnit.DAYS);
        long randomSec = random.nextLong() % (730L * 24 * 60 * 60);
        if (randomSec < 0) randomSec = -randomSec;
        Instant orderDate = lastTwoYears.plusSeconds(randomSec);
        return new OrderRecord(orderId, userId, Timestamp.from(orderDate), totalAmount);
    }

    public OrderItemRecord generateOrderItem(long orderItemId, long orderId, long productId) {
        int quantity = random.nextInt(5) + 1; // 1 to 5 items
        BigDecimal pricePerUnit = BigDecimal.valueOf(5 + random.nextDouble() * 495).setScale(2, RoundingMode.HALF_UP);
        return new OrderItemRecord(orderItemId, orderId, productId, quantity, pricePerUnit);
    }

    public record UserRecord(long userId, String username, String email, String passwordHash, boolean isActive) {}
    public record ProductRecord(long productId, String productName, BigDecimal price, Timestamp createdAt) {}
    public record OrderRecord(long orderId, long userId, Timestamp orderDate, BigDecimal totalAmount) {}
    public record OrderItemRecord(long orderItemId, long orderId, long productId, int quantity, BigDecimal pricePerUnit) {}
}
