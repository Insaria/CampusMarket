USE campus_market_dev;

SHOW CREATE TABLE merchants;
SHOW CREATE TABLE settlements;
SELECT COUNT(*) AS existing_business_profiles,
       COALESCE(SUM(business_license_ref IS NULL OR CHAR_LENGTH(TRIM(business_license_ref)) = 0
                    OR identity_document_ref IS NULL OR CHAR_LENGTH(TRIM(identity_document_ref)) = 0), 0)
           AS profiles_missing_credentials
FROM merchants;

ALTER TABLE merchants
    ADD COLUMN seller_type VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'BUSINESS',
    MODIFY business_license_ref VARCHAR(512) NULL DEFAULT NULL,
    MODIFY identity_document_ref VARCHAR(512) NULL DEFAULT NULL,
    ADD CONSTRAINT ck_merchants_seller_type CHECK (seller_type IN ('PERSONAL', 'BUSINESS')),
    ADD CONSTRAINT ck_merchants_seller_credentials CHECK (
        (seller_type = 'PERSONAL' AND business_license_ref IS NULL AND identity_document_ref IS NULL
            AND level_id IS NULL)
        OR (seller_type = 'BUSINESS' AND business_license_ref IS NOT NULL
            AND CHAR_LENGTH(TRIM(business_license_ref)) > 0 AND identity_document_ref IS NOT NULL
            AND CHAR_LENGTH(TRIM(identity_document_ref)) > 0
            AND (operation_status <> 'ACTIVE' OR level_id IS NOT NULL))
    );

ALTER TABLE settlements
    DROP CHECK ck_settlements_fee_snapshot,
    MODIFY level_code_snapshot TINYINT NULL DEFAULT NULL,
    ADD CONSTRAINT ck_settlements_seller_fee_snapshot CHECK (
        (level_code_snapshot IS NULL AND fee_rate_snapshot = 0 AND fee_amount = 0)
        OR (level_code_snapshot IS NOT NULL AND level_code_snapshot BETWEEN 1 AND 5
            AND fee_rate_snapshot BETWEEN 0 AND 1)
    );

SHOW CREATE TABLE merchants;
SHOW CREATE TABLE settlements;
