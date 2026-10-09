-- Three tabs on each till: every open cart of a cashier sits in tab 1, 2 or 3, so the cashier can serve the next
-- customer while the first one fetches something they forgot. Parked carts have no tab.
ALTER TABLE carts ADD COLUMN slot INT;
UPDATE carts SET slot = 1 WHERE status = 'OPEN';
