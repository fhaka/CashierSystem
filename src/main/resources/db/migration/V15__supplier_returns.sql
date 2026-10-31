-- Returns to suppliers (Kthimet te furnitorët). The goods leave stock at once (a SUPPLIER_RETURN stock adjustment).
-- CREDIT: the supplier takes it off what the shop owes (a credit note). REPLACEMENT: no money moves; the return waits
-- (PENDING) until the same goods come back (REPLACED, a REPLACEMENT stock adjustment).
CREATE TABLE supplier_returns (
    id BIGINT NOT NULL AUTO_INCREMENT,
    number VARCHAR(20) NOT NULL,
    supplier_id BIGINT NOT NULL,
    kind VARCHAR(12) NOT NULL,
    status VARCHAR(12) NOT NULL,
    reason VARCHAR(20) NOT NULL,
    note VARCHAR(255),
    total_amount DECIMAL(12,2) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    replaced_by BIGINT,
    replaced_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_supplier_returns_number UNIQUE (number),
    CONSTRAINT fk_supplier_returns_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id),
    CONSTRAINT fk_supplier_returns_created_by FOREIGN KEY (created_by) REFERENCES cashiers (id),
    CONSTRAINT fk_supplier_returns_replaced_by FOREIGN KEY (replaced_by) REFERENCES cashiers (id)
);
CREATE INDEX idx_supplier_returns_created_at ON supplier_returns (created_at);

-- quantity in pieces or kg; price per piece/kg as on purchase invoices (with VAT unless pos.reports.cost-includes-vat=false).
CREATE TABLE supplier_return_lines (
    id BIGINT NOT NULL AUTO_INCREMENT,
    return_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity DECIMAL(12,3) NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    tax_rate DECIMAL(5,2) NOT NULL,
    line_total DECIMAL(12,2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_supplier_return_lines_return FOREIGN KEY (return_id) REFERENCES supplier_returns (id),
    CONSTRAINT fk_supplier_return_lines_product FOREIGN KEY (product_id) REFERENCES products (id)
);

INSERT INTO number_sequences (name, current_value) VALUES ('SUPPLIER_RETURN', 0);
