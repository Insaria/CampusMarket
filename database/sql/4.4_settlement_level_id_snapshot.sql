USE campus_market_dev;

-- Apply once after 4.2.1_seller_types_migration.sql.
-- Historical rows keep NULL when the original level ID was not captured.
ALTER TABLE settlements
    ADD COLUMN level_id_snapshot BIGINT NULL DEFAULT NULL AFTER seller_credit_entry_id,
    ADD CONSTRAINT fk_settlements_level_snapshot FOREIGN KEY (level_id_snapshot)
        REFERENCES merchant_levels (id) ON DELETE RESTRICT ON UPDATE RESTRICT;

SHOW CREATE TABLE settlements;
