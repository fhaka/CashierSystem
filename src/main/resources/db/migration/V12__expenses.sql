-- Shop expenses (rent, electricity, salaries...) for the profit and loss report. An expense paid in cash leaves the till
-- of the cashier's open shift as a cash movement; one paid by bank or card is only recorded.
CREATE TABLE expense_categories (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    active BOOLEAN DEFAULT TRUE NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_expense_categories_name UNIQUE (name)
);
INSERT INTO expense_categories (name, active, created_at) VALUES
    ('Qira', TRUE, CURRENT_TIMESTAMP),
    ('Energji elektrike', TRUE, CURRENT_TIMESTAMP),
    ('Ujë', TRUE, CURRENT_TIMESTAMP),
    ('Paga', TRUE, CURRENT_TIMESTAMP),
    ('Sigurime shoqërore', TRUE, CURRENT_TIMESTAMP),
    ('Taksa vendore', TRUE, CURRENT_TIMESTAMP),
    ('Transport dhe karburant', TRUE, CURRENT_TIMESTAMP),
    ('Telefon dhe internet', TRUE, CURRENT_TIMESTAMP),
    ('Mirëmbajtje dhe riparime', TRUE, CURRENT_TIMESTAMP),
    ('Materiale pastrimi dhe ambalazhe', TRUE, CURRENT_TIMESTAMP),
    ('Kontabilist', TRUE, CURRENT_TIMESTAMP),
    ('Të tjera', TRUE, CURRENT_TIMESTAMP);

CREATE TABLE expenses (
    id BIGINT NOT NULL AUTO_INCREMENT,
    category_id BIGINT NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    paid_on DATE NOT NULL,
    method VARCHAR(10) NOT NULL,
    paid_to VARCHAR(150),
    note VARCHAR(255),
    shift_id BIGINT,
    cash_movement_id BIGINT,
    cashier_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    cancelled BOOLEAN DEFAULT FALSE NOT NULL,
    cancelled_by BIGINT,
    cancelled_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_expenses_category FOREIGN KEY (category_id) REFERENCES expense_categories (id),
    CONSTRAINT fk_expenses_shift FOREIGN KEY (shift_id) REFERENCES shifts (id),
    CONSTRAINT fk_expenses_cash_movement FOREIGN KEY (cash_movement_id) REFERENCES cash_movements (id),
    CONSTRAINT fk_expenses_cashier FOREIGN KEY (cashier_id) REFERENCES cashiers (id),
    CONSTRAINT fk_expenses_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES cashiers (id)
);
CREATE INDEX idx_expenses_paid_on ON expenses (paid_on);
