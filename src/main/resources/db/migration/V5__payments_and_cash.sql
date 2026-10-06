-- Exchange rates are set once by a manager and used by every till.
-- buy_rate: LEK given for 1 unit when a customer pays in that currency.
-- sell_rate: used to show prices in that currency.
CREATE TABLE exchange_rates (
    currency VARCHAR(3) NOT NULL,
    buy_rate DECIMAL(10,4) NOT NULL,
    sell_rate DECIMAL(10,4) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (currency)
);
INSERT INTO exchange_rates (currency, buy_rate, sell_rate, updated_at) VALUES ('EUR', 95.7000, 95.1000, CURRENT_TIMESTAMP);
INSERT INTO exchange_rates (currency, buy_rate, sell_rate, updated_at) VALUES ('USD', 82.7000, 81.7000, CURRENT_TIMESTAMP);

-- Every sale records how it was paid and which shift it belongs to.
ALTER TABLE sales ADD COLUMN shift_id BIGINT;
ALTER TABLE sales ADD COLUMN paid_amount DECIMAL(12,2);
ALTER TABLE sales ADD COLUMN change_amount DECIMAL(12,2) DEFAULT 0 NOT NULL;
ALTER TABLE sales ADD CONSTRAINT fk_sales_shift FOREIGN KEY (shift_id) REFERENCES shifts (id);
CREATE INDEX idx_sales_date ON sales (date);

CREATE TABLE sale_payments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    sale_id BIGINT NOT NULL,
    method VARCHAR(10) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    exchange_rate DECIMAL(10,4) NOT NULL,
    amount_lek DECIMAL(12,2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_sale_payments_sale FOREIGN KEY (sale_id) REFERENCES sales (id)
);

-- Sales made before payments were recorded were all paid in exact cash in LEK.
UPDATE sales SET paid_amount = total_amount;
INSERT INTO sale_payments (sale_id, method, currency, amount, exchange_rate, amount_lek)
    SELECT id, 'CASH', 'LEK', total_amount, 1, total_amount FROM sales;
-- ...and belonged to the shift their cashier had open at that time.
UPDATE sales SET shift_id = (
    SELECT MAX(sh.id) FROM shifts sh
    WHERE sh.cashier_id = sales.cashier_id
      AND sales.date >= sh.opened_at
      AND (sh.closed_at IS NULL OR sales.date <= sh.closed_at)
);

-- Money put into or taken out of the drawer during a shift (change float, paying a supplier...).
CREATE TABLE cash_movements (
    id BIGINT NOT NULL AUTO_INCREMENT,
    shift_id BIGINT NOT NULL,
    cashier_id BIGINT NOT NULL,
    movement_type VARCHAR(3) NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_cash_movements_shift FOREIGN KEY (shift_id) REFERENCES shifts (id),
    CONSTRAINT fk_cash_movements_cashier FOREIGN KEY (cashier_id) REFERENCES cashiers (id)
);

-- Closed shifts keep the breakdown behind their expected cash.
ALTER TABLE shifts ADD COLUMN cash_sales DECIMAL(12,2);
ALTER TABLE shifts ADD COLUMN card_sales DECIMAL(12,2);
ALTER TABLE shifts ADD COLUMN cash_in DECIMAL(12,2);
ALTER TABLE shifts ADD COLUMN cash_out DECIMAL(12,2);
