-- Shop details and receipt options, edited by the administrator on the Settings screen. One row per setting;
-- a missing row means the default.
CREATE TABLE shop_settings (
    setting_key VARCHAR(64) NOT NULL,
    setting_value VARCHAR(1000) NOT NULL,
    PRIMARY KEY (setting_key)
);

-- The receipt exactly as it was printed, so a reprint is identical (marked as a copy).
ALTER TABLE sales ADD COLUMN receipt_text TEXT;
