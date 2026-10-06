-- Selling by weight: quantities and stock with up to 3 decimals (0.350 kg).
ALTER TABLE products MODIFY COLUMN stock DECIMAL(12,3) NOT NULL;
ALTER TABLE sale_items MODIFY COLUMN quantity DECIMAL(12,3) NOT NULL;
ALTER TABLE purchase_items MODIFY COLUMN quantity DECIMAL(12,3) NOT NULL;

-- Products are deactivated instead of deleted, so old sales keep pointing at them.
ALTER TABLE products ADD COLUMN active BOOLEAN DEFAULT TRUE NOT NULL;

-- Each sale line keeps its cost (for profit), its share of the discount and its final amount.
ALTER TABLE sale_items ADD COLUMN purchase_price DECIMAL(10,2);
ALTER TABLE sale_items ADD COLUMN discount_amount DECIMAL(12,2) DEFAULT 0 NOT NULL;
ALTER TABLE sale_items ADD COLUMN line_total DECIMAL(12,2);
UPDATE sale_items SET line_total = ROUND(price * quantity, 2);
-- Older sales did not record their cost: use today's cost price as the best available estimate.
UPDATE sale_items SET purchase_price = (SELECT p.purchase_price FROM products p WHERE p.id = sale_items.product_id);

-- Invoice numbers come from a counter locked inside the sale transaction, so they have no gaps.
-- Older sales get their id as number and the counter continues after the highest one.
ALTER TABLE sales ADD COLUMN invoice_number VARCHAR(20);
ALTER TABLE sales ADD COLUMN discount_amount DECIMAL(12,2) DEFAULT 0 NOT NULL;
UPDATE sales SET invoice_number = LPAD(CONCAT('', id), 6, '0');
CREATE UNIQUE INDEX uk_sales_invoice_number ON sales (invoice_number);

CREATE TABLE number_sequences (
    name VARCHAR(40) NOT NULL,
    current_value BIGINT NOT NULL,
    PRIMARY KEY (name)
);
INSERT INTO number_sequences (name, current_value) SELECT 'SALE_INVOICE', COALESCE(MAX(id), 0) FROM sales;

-- Carts live in the database: they survive a restart and can be parked and resumed on any till.
CREATE TABLE carts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    cashier_id BIGINT NOT NULL,
    status VARCHAR(10) NOT NULL,
    label VARCHAR(100),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_carts_cashier FOREIGN KEY (cashier_id) REFERENCES cashiers (id)
);
CREATE INDEX idx_carts_cashier_status ON carts (cashier_id, status);

CREATE TABLE cart_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    cart_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    product_name VARCHAR(255) NOT NULL,
    barcode VARCHAR(255) NOT NULL,
    quantity DECIMAL(12,3) NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    tax_rate DECIMAL(5,2) NOT NULL,
    unit VARCHAR(10) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_cart_items_cart FOREIGN KEY (cart_id) REFERENCES carts (id) ON DELETE CASCADE,
    CONSTRAINT fk_cart_items_product FOREIGN KEY (product_id) REFERENCES products (id)
);
