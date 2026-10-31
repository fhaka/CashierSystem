-- Orders to suppliers (Porositë te furnitorët): what the shop asks a supplier to bring. They do not move stock; the
-- goods come in with the supplier's purchase invoice, and the order is then marked as received.
CREATE TABLE purchase_orders (
    id BIGINT NOT NULL AUTO_INCREMENT,
    number VARCHAR(20) NOT NULL,
    supplier_id BIGINT NOT NULL,
    status VARCHAR(12) NOT NULL,
    note VARCHAR(255),
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    sent_at DATETIME(6),
    closed_by BIGINT,
    closed_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_purchase_orders_number UNIQUE (number),
    CONSTRAINT fk_purchase_orders_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id),
    CONSTRAINT fk_purchase_orders_created_by FOREIGN KEY (created_by) REFERENCES cashiers (id),
    CONSTRAINT fk_purchase_orders_closed_by FOREIGN KEY (closed_by) REFERENCES cashiers (id)
);
CREATE INDEX idx_purchase_orders_status ON purchase_orders (status);

-- quantity: boxes when package_id is set, otherwise pieces or kg of the product; price per box or per piece/kg.
CREATE TABLE purchase_order_lines (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    package_id BIGINT,
    quantity DECIMAL(12,3) NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_purchase_order_lines_order FOREIGN KEY (order_id) REFERENCES purchase_orders (id),
    CONSTRAINT fk_purchase_order_lines_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_purchase_order_lines_package FOREIGN KEY (package_id) REFERENCES product_packages (id)
);

INSERT INTO number_sequences (name, current_value) VALUES ('PURCHASE_ORDER', 0);
