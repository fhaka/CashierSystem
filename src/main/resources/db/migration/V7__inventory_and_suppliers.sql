-- Suppliers as real records. Existing purchase invoices name their supplier as free text:
-- every distinct name becomes a supplier and the invoices are linked to it.
CREATE TABLE suppliers (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(150) NOT NULL,
    tax_number VARCHAR(30),
    phone VARCHAR(50),
    email VARCHAR(150),
    address VARCHAR(255),
    notes VARCHAR(500),
    active BOOLEAN DEFAULT TRUE NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_suppliers_name UNIQUE (name)
);
INSERT INTO suppliers (name, active, created_at)
    SELECT MIN(TRIM(company)), TRUE, CURRENT_TIMESTAMP FROM purchase_invoices GROUP BY LOWER(TRIM(company));

ALTER TABLE purchase_invoices ADD COLUMN supplier_id BIGINT;
ALTER TABLE purchase_invoices ADD CONSTRAINT fk_purchase_invoices_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id);
UPDATE purchase_invoices SET supplier_id = (
    SELECT MIN(s.id) FROM suppliers s WHERE LOWER(s.name) = LOWER(TRIM(purchase_invoices.company))
);

-- Payments to suppliers. Amount owed = invoices - payments.
CREATE TABLE supplier_payments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    supplier_id BIGINT NOT NULL,
    cashier_id BIGINT NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    paid_on DATE NOT NULL,
    method VARCHAR(10) NOT NULL,
    note VARCHAR(255),
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_supplier_payments_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id),
    CONSTRAINT fk_supplier_payments_cashier FOREIGN KEY (cashier_id) REFERENCES cashiers (id)
);

-- Reordering: alert below min_stock, suggest reorder_quantity (or enough to reach twice the minimum).
ALTER TABLE products ADD COLUMN min_stock DECIMAL(12,3);
ALTER TABLE products ADD COLUMN reorder_quantity DECIMAL(12,3);

-- Every manual stock change, with its reason. Stock counts create COUNT_CORRECTION adjustments.
CREATE TABLE stock_adjustments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    cashier_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    quantity_change DECIMAL(12,3) NOT NULL,
    stock_after DECIMAL(12,3) NOT NULL,
    reason VARCHAR(20) NOT NULL,
    note VARCHAR(255),
    inventory_count_id BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT fk_stock_adjustments_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_stock_adjustments_cashier FOREIGN KEY (cashier_id) REFERENCES cashiers (id)
);
CREATE INDEX idx_stock_adjustments_product ON stock_adjustments (product_id, created_at);

-- Stock-taking: count what is on the shelf, then apply the differences in one go.
CREATE TABLE inventory_counts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    started_by_id BIGINT NOT NULL,
    started_at DATETIME(6) NOT NULL,
    status VARCHAR(10) NOT NULL,
    finished_by_id BIGINT,
    finished_at DATETIME(6),
    note VARCHAR(255),
    PRIMARY KEY (id),
    CONSTRAINT fk_inventory_counts_started_by FOREIGN KEY (started_by_id) REFERENCES cashiers (id),
    CONSTRAINT fk_inventory_counts_finished_by FOREIGN KEY (finished_by_id) REFERENCES cashiers (id)
);

CREATE TABLE inventory_count_lines (
    id BIGINT NOT NULL AUTO_INCREMENT,
    count_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    counted_quantity DECIMAL(12,3) NOT NULL,
    counted_at DATETIME(6) NOT NULL,
    counted_by_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_inventory_count_lines UNIQUE (count_id, product_id),
    CONSTRAINT fk_inventory_count_lines_count FOREIGN KEY (count_id) REFERENCES inventory_counts (id),
    CONSTRAINT fk_inventory_count_lines_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_inventory_count_lines_counted_by FOREIGN KEY (counted_by_id) REFERENCES cashiers (id)
);

-- Expiry dates, recorded per delivery.
ALTER TABLE purchase_items ADD COLUMN expiry_date DATE;
CREATE INDEX idx_purchase_items_expiry ON purchase_items (expiry_date);
