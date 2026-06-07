INSERT INTO categories (id, name) VALUES (1, 'Dairy');
INSERT INTO categories (id, name) VALUES (2, 'Drinks');
INSERT INTO categories (id, name) VALUES (3, 'Household Cleaning');
INSERT INTO categories (id, name) VALUES (4, 'Snacks');
INSERT INTO categories (id, name) VALUES (5, 'Meat and Poultry');
INSERT INTO categories (id, name) VALUES (6, 'Clothes');
INSERT INTO categories (id, name) VALUES (7, 'Food');
INSERT INTO categories (id, name) VALUES (8, 'Tobacco');
INSERT INTO categories (id, name) VALUES (9, 'Personal Care');
INSERT INTO categories (id, name) VALUES (10, 'Fruits and Vegetables');
INSERT INTO categories (id, name) VALUES (11, 'Household');
INSERT INTO categories (id, name) VALUES (12, 'Baby Products');
INSERT INTO categories (id, name) VALUES (13, 'Others');

INSERT INTO cashiers (id, full_name, username, password_hash) VALUES (1, 'Cashier One', 'cashier', '03ac674216f3e15c761ee1a5e255f067953623c8b388b4459e13f978d7c846f4');

INSERT INTO products (id, name, barcode, price, purchase_price, tax_rate, stock, unit, category_id) VALUES (1, 'Bread 800 Gr', '100000000001', 100.00, 75.00, 20.00, 50, 'pcs', 7);
INSERT INTO products (id, name, barcode, price, purchase_price, tax_rate, stock, unit, category_id) VALUES (2, 'Milk', '100000000002', 200, 180, 20.00, 35, 'pcs', 1);
INSERT INTO products (id, name, barcode, price, purchase_price, tax_rate, stock, unit, category_id) VALUES (3, 'Rice 1 Kg Bag', '100000000003', 180, 160, 20.00, 100, 'pcs', 7);
INSERT INTO products (id, name, barcode, price, purchase_price, tax_rate, stock, unit, category_id) VALUES (4, 'Soap', '100000000004', 120, 95, 20.00, 40, 'pcs', 3);
INSERT INTO products (id, name, barcode, price, purchase_price, tax_rate, stock, unit, category_id) VALUES (5, 'Gold Cigarette', '100000000005', 400.00, 376, 0.00, 200, 'pcs', 8);
INSERT INTO products (id, name, barcode, price, purchase_price, tax_rate, stock, unit, category_id) VALUES (6, 'Chocolate', '100000000006', 150, 132, 20.00, 20, 'pcs', 4);
INSERT INTO products (id, name, barcode, price, purchase_price, tax_rate, stock, unit, category_id) VALUES (7, 'Orbit Spearmint', '100000000007', 70, 55, 20.00, 20, 'pcs', 4);
INSERT INTO products (id, name, barcode, price, purchase_price, tax_rate, stock, unit, category_id) VALUES (8, 'Plastic Bag', '0001', 20, 12, 20.00, 1000, 'pcs', 13);

INSERT INTO products (id, name, barcode, price, purchase_price, tax_rate, stock, unit, category_id) VALUES (9, 'Pasta 500 GR Bag', '100000000008', 120.00, 105.00, 20.00, 100, 'pcs', 7);
INSERT INTO products (id, name, barcode, price, purchase_price, tax_rate, stock, unit, category_id) VALUES (10, 'Greek Yogurt 1 Lt', '100000000009', 160.00, 143.00, 20.00, 35, 'pcs', 1);
INSERT INTO products (id, name, barcode, price, purchase_price, tax_rate, stock, unit, category_id) VALUES (11, 'Sugar 1kg', '100000000010', 80, 65, 20.00, 200, 'pcs', 7);
INSERT INTO products (id, name, barcode, price, purchase_price, tax_rate, stock, unit, category_id) VALUES (12, 'Sea Salt', '100000000011', 30, 18.00, 20.00, 70, 'pcs', 7);
INSERT INTO products (id, name, barcode, price, purchase_price, tax_rate, stock, unit, category_id) VALUES (13, 'Red Cigarette', '100000000012', 400, 376, 0.00, 200, 'pcs', 8);
INSERT INTO products (id, name, barcode, price, purchase_price, tax_rate, stock, unit, category_id) VALUES (14, 'Dash Detergent', '100000000013', 2600, 2370, 20.00, 12, 'pcs', 3);
INSERT INTO products (id, name, barcode, price, purchase_price, tax_rate, stock, unit, category_id) VALUES (15, 'Shampoo', '100000000014', 390.00, 280.00, 20.00, 20, 'pcs', 9);
INSERT INTO products (id, name, barcode, price, purchase_price, tax_rate, stock, unit, category_id) VALUES (16, 'Toothpaste', '100000000015', 390.00, 280.00, 20.00, 20, 'pcs', 9);

INSERT INTO products (id, name, barcode, price, purchase_price, tax_rate, stock, unit, category_id) VALUES (17, 'Feta Cheese', '100000000016', 750, 700.00, 20.00, 96, 'kg', 1);
INSERT INTO products (id, name, barcode, price, purchase_price, tax_rate, stock, unit, category_id) VALUES (18, 'Cheese', '100000000017', 1200, 980.00, 20.00, 78, 'kg', 1);
INSERT INTO products (id, name, barcode, price, purchase_price, tax_rate, stock, unit, category_id) VALUES (19, 'Chicken Wings 800gr', '100000000018', 390.00, 280.00, 20.00, 20, 'pcs', 5);
INSERT INTO products (id, name, barcode, price, purchase_price, tax_rate, stock, unit, category_id) VALUES (20, 'Mozzarella', '100000000019', 470, 420, 20.00, 35, 'kg', 1);
INSERT INTO products (id, name, barcode, price, purchase_price, tax_rate, stock, unit, category_id) VALUES (21, 'Red Wine', '100000000020', 800, 680, 20.00, 40, 'pcs', 2);
INSERT INTO products (id, name, barcode, price, purchase_price, tax_rate, stock, unit, category_id) VALUES (22, 'Soda', '100000000021', 90, 71, 20.00, 150, 'pcs', 2);
INSERT INTO products (id, name, barcode, price, purchase_price, tax_rate, stock, unit, category_id) VALUES (23, 'Beer', '100000000022', 120, 96, 20.00, 200, 'pcs', 2);
INSERT INTO products (id, name, barcode, price, purchase_price, tax_rate, stock, unit, category_id) VALUES (24, 'Vodka', '100000000023', 1700, 1420, 20.00, 30, 'pcs', 2);


ALTER TABLE categories ALTER COLUMN id RESTART WITH 14;
ALTER TABLE cashiers ALTER COLUMN id RESTART WITH 2;
ALTER TABLE products ALTER COLUMN id RESTART WITH 25;
