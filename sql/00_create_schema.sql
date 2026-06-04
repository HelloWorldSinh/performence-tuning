-- =============================================================================
-- Schema: Performance Tuning Demo
-- Tables: users, products, orders, order_items
-- Note:   Table names are lowercase to match PostgreSQL conventions
--         and avoid quoted-identifier issues.
-- =============================================================================

-- 1. users
CREATE TABLE IF NOT EXISTS users (
    user_id       BIGSERIAL    PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    email         VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    is_active     BOOLEAN      DEFAULT TRUE
);

-- 2. products
CREATE TABLE IF NOT EXISTS products (
    product_id   BIGSERIAL       PRIMARY KEY,
    product_name VARCHAR(150)    NOT NULL,
    price        NUMERIC(12, 2)  NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 3. orders
--    status column added for Indexing & Pagination benchmark.
--    DEFAULT 'DELIVERED' allows existing DataGenerator inserts
--    (which do not supply status) to succeed without code changes.
CREATE TABLE IF NOT EXISTS orders (
    order_id     BIGSERIAL       PRIMARY KEY,
    user_id      BIGINT          NOT NULL,
    order_date   TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    total_amount NUMERIC(12, 2)  NOT NULL,
    status       VARCHAR(20)     DEFAULT 'DELIVERED'
                   CHECK (status IN ('DELIVERED', 'PENDING', 'CANCELED')),
    CONSTRAINT fk_orders_user FOREIGN KEY (user_id)
        REFERENCES users(user_id) ON DELETE RESTRICT
);

-- 4. order_items
CREATE TABLE IF NOT EXISTS order_items (
    order_item_id BIGSERIAL       PRIMARY KEY,
    order_id      BIGINT          NOT NULL,
    product_id    BIGINT          NOT NULL,
    quantity      INT             NOT NULL CHECK (quantity > 0),
    price_per_unit NUMERIC(12, 2) NOT NULL,
    CONSTRAINT fk_items_order FOREIGN KEY (order_id)
        REFERENCES orders(order_id) ON DELETE CASCADE,
    CONSTRAINT fk_items_product FOREIGN KEY (product_id)
        REFERENCES products(product_id) ON DELETE RESTRICT
);
