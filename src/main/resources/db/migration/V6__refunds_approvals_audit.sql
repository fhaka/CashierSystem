-- Who did what and when. Never updated or deleted by the application.
CREATE TABLE audit_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    cashier_id BIGINT,
    approved_by_id BIGINT,
    action VARCHAR(40) NOT NULL,
    entity_type VARCHAR(40),
    entity_id VARCHAR(40),
    details VARCHAR(1000),
    PRIMARY KEY (id),
    CONSTRAINT fk_audit_cashier FOREIGN KEY (cashier_id) REFERENCES cashiers (id),
    CONSTRAINT fk_audit_approver FOREIGN KEY (approved_by_id) REFERENCES cashiers (id)
);
CREATE INDEX idx_audit_created_at ON audit_events (created_at);
CREATE INDEX idx_audit_action ON audit_events (action);

-- Managers approve voids, refunds and price changes at a till with a personal PIN (stored with BCrypt).
ALTER TABLE cashiers ADD COLUMN approval_pin_hash VARCHAR(100);

-- Refunds: money and stock going back for (part of) an earlier sale.
CREATE TABLE refunds (
    id BIGINT NOT NULL AUTO_INCREMENT,
    refund_number VARCHAR(20) NOT NULL,
    sale_id BIGINT NOT NULL,
    shift_id BIGINT NOT NULL,
    cashier_id BIGINT NOT NULL,
    approved_by_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    method VARCHAR(10) NOT NULL,
    total_amount DECIMAL(12,2) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_refunds_number UNIQUE (refund_number),
    CONSTRAINT fk_refunds_sale FOREIGN KEY (sale_id) REFERENCES sales (id),
    CONSTRAINT fk_refunds_shift FOREIGN KEY (shift_id) REFERENCES shifts (id),
    CONSTRAINT fk_refunds_cashier FOREIGN KEY (cashier_id) REFERENCES cashiers (id),
    CONSTRAINT fk_refunds_approver FOREIGN KEY (approved_by_id) REFERENCES cashiers (id)
);
CREATE INDEX idx_refunds_created_at ON refunds (created_at);

CREATE TABLE refund_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    refund_id BIGINT NOT NULL,
    sale_item_id BIGINT NOT NULL,
    quantity DECIMAL(12,3) NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    tax_rate DECIMAL(5,2) NOT NULL,
    tax_amount DECIMAL(12,2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_refund_items_refund FOREIGN KEY (refund_id) REFERENCES refunds (id),
    CONSTRAINT fk_refund_items_sale_item FOREIGN KEY (sale_item_id) REFERENCES sale_items (id)
);

INSERT INTO number_sequences (name, current_value) VALUES ('REFUND', 0);

-- Closed shifts keep refunds paid out, by method.
ALTER TABLE shifts ADD COLUMN cash_refunds DECIMAL(12,2);
ALTER TABLE shifts ADD COLUMN card_refunds DECIMAL(12,2);
