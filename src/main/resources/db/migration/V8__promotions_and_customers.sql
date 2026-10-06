-- Promotions applied automatically at the till.
-- PERCENT: discount_percent off; BUY_X_GET_Y: for every buy_quantity + free_quantity pieces, free_quantity are free.
-- Scope: one product or a whole category. Optional date range, days of week (1=Monday ... 7=Sunday) and hours.
CREATE TABLE promotions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    promo_type VARCHAR(20) NOT NULL,
    discount_percent DECIMAL(5,2),
    buy_quantity INT,
    free_quantity INT,
    product_id BIGINT,
    category_id BIGINT,
    starts_on DATE,
    ends_on DATE,
    days_of_week VARCHAR(20),
    start_time TIME,
    end_time TIME,
    active BOOLEAN DEFAULT TRUE NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_promotions_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_promotions_category FOREIGN KEY (category_id) REFERENCES categories (id)
);

-- Customers: loyalty card, points, and an account for buying on credit.
CREATE TABLE customers (
    id BIGINT NOT NULL AUTO_INCREMENT,
    card_number VARCHAR(40) NOT NULL,
    full_name VARCHAR(150) NOT NULL,
    phone VARCHAR(50),
    email VARCHAR(150),
    notes VARCHAR(500),
    points INT DEFAULT 0 NOT NULL,
    credit_limit DECIMAL(12,2),
    balance DECIMAL(12,2) DEFAULT 0 NOT NULL,
    active BOOLEAN DEFAULT TRUE NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_customers_card UNIQUE (card_number)
);

-- Every change of a customer's points or debt, with what caused it.
CREATE TABLE customer_transactions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    cashier_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    tx_type VARCHAR(20) NOT NULL,
    points_change INT DEFAULT 0 NOT NULL,
    balance_change DECIMAL(12,2) DEFAULT 0 NOT NULL,
    sale_id BIGINT,
    refund_id BIGINT,
    note VARCHAR(255),
    PRIMARY KEY (id),
    CONSTRAINT fk_customer_tx_customer FOREIGN KEY (customer_id) REFERENCES customers (id),
    CONSTRAINT fk_customer_tx_cashier FOREIGN KEY (cashier_id) REFERENCES cashiers (id),
    CONSTRAINT fk_customer_tx_sale FOREIGN KEY (sale_id) REFERENCES sales (id),
    CONSTRAINT fk_customer_tx_refund FOREIGN KEY (refund_id) REFERENCES refunds (id)
);
CREATE INDEX idx_customer_tx_customer ON customer_transactions (customer_id, created_at);

-- The cart on screen can have a customer and a manual discount for the whole cart.
ALTER TABLE carts ADD COLUMN customer_id BIGINT;
ALTER TABLE carts ADD CONSTRAINT fk_carts_customer FOREIGN KEY (customer_id) REFERENCES customers (id);
ALTER TABLE carts ADD COLUMN manual_discount_percent DECIMAL(5,2);

ALTER TABLE sales ADD COLUMN customer_id BIGINT;
ALTER TABLE sales ADD CONSTRAINT fk_sales_customer FOREIGN KEY (customer_id) REFERENCES customers (id);
ALTER TABLE sales ADD COLUMN points_earned INT DEFAULT 0 NOT NULL;

-- Part of a line's discount that came from a promotion, and which one.
ALTER TABLE sale_items ADD COLUMN promotion_id BIGINT;
ALTER TABLE sale_items ADD CONSTRAINT fk_sale_items_promotion FOREIGN KEY (promotion_id) REFERENCES promotions (id);
ALTER TABLE sale_items ADD COLUMN promotion_discount DECIMAL(12,2) DEFAULT 0 NOT NULL;

ALTER TABLE shifts ADD COLUMN credit_sales DECIMAL(12,2);
