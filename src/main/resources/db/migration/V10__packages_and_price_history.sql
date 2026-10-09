-- Boxes (packages): a box has its own barcode and holds a number of pieces of one product. Stock is kept only
-- on the product, in pieces; selling, refunding or buying a box moves that many pieces. A box may have its own
-- price (often cheaper than the pieces one by one); without one it costs pieces x the piece price.
CREATE TABLE product_packages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    barcode VARCHAR(255) NOT NULL,
    name VARCHAR(100) NOT NULL,
    pieces INT NOT NULL,
    price DECIMAL(10,2),
    active BOOLEAN DEFAULT TRUE NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_product_packages_barcode UNIQUE (barcode),
    CONSTRAINT fk_product_packages_product FOREIGN KEY (product_id) REFERENCES products (id)
);

-- A cart line for a box: quantity counts boxes, price is the price of one box.
ALTER TABLE cart_items ADD COLUMN package_id BIGINT;
ALTER TABLE cart_items ADD COLUMN package_name VARCHAR(100);
ALTER TABLE cart_items ADD COLUMN pieces_per_unit INT;

-- A sale line always counts pieces (stock, cost, reports and refunds work on pieces); for a box it also keeps
-- how many boxes at what price, for the receipt.
ALTER TABLE sale_items ADD COLUMN package_id BIGINT;
ALTER TABLE sale_items ADD COLUMN package_name VARCHAR(100);
ALTER TABLE sale_items ADD COLUMN package_count DECIMAL(12,3);
ALTER TABLE sale_items ADD COLUMN package_price DECIMAL(10,2);
ALTER TABLE sale_items ADD CONSTRAINT fk_sale_items_package FOREIGN KEY (package_id) REFERENCES product_packages (id);

-- A purchase line bought in boxes: quantity and purchase price are per piece, package_count the boxes received.
ALTER TABLE purchase_items ADD COLUMN package_id BIGINT;
ALTER TABLE purchase_items ADD COLUMN package_count DECIMAL(12,3);
ALTER TABLE purchase_items ADD CONSTRAINT fk_purchase_items_package FOREIGN KEY (package_id) REFERENCES product_packages (id);

-- What a piece contains (0.5 l, 330 g), for the price per litre or kilogram on shelf labels.
ALTER TABLE products ADD COLUMN content_amount DECIMAL(10,3);
ALTER TABLE products ADD COLUMN content_unit VARCHAR(5);

-- Every change of a selling or purchase price: when, by whom, from where (EDIT, PURCHASE, BULK, IMPORT).
CREATE TABLE price_changes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    package_id BIGINT,
    changed_at DATETIME(6) NOT NULL,
    cashier_id BIGINT,
    source VARCHAR(20) NOT NULL,
    old_price DECIMAL(10,2),
    new_price DECIMAL(10,2),
    old_purchase_price DECIMAL(10,2),
    new_purchase_price DECIMAL(10,2),
    PRIMARY KEY (id),
    CONSTRAINT fk_price_changes_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_price_changes_package FOREIGN KEY (package_id) REFERENCES product_packages (id),
    CONSTRAINT fk_price_changes_cashier FOREIGN KEY (cashier_id) REFERENCES cashiers (id)
);
CREATE INDEX idx_price_changes_product ON price_changes (product_id, changed_at);
CREATE INDEX idx_price_changes_date ON price_changes (changed_at);
