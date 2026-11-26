SET SESSION time_zone = '+00:00';

CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    login_name VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role_code VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'USER',
    real_name VARCHAR(64) NOT NULL,
    phone VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,
    email VARCHAR(254) COLLATE utf8mb4_0900_bin NOT NULL,
    city VARCHAR(64) NULL DEFAULT NULL,
    gender_code VARCHAR(16) COLLATE utf8mb4_0900_bin NULL DEFAULT NULL,
    bank_account_enc VARBINARY(128) NULL DEFAULT NULL,
    registration_status VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'PENDING',
    account_status VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'ENABLED',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_users_login_name (login_name),
    KEY ix_users_registration_queue (registration_status, created_at, id),
    CONSTRAINT ck_users_role_code CHECK (role_code IN ('USER', 'ADMIN')),
    CONSTRAINT ck_users_registration_status CHECK (registration_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_users_account_status CHECK (account_status IN ('ENABLED', 'DISABLED', 'DELETED'))
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE merchant_levels (
    id BIGINT NOT NULL AUTO_INCREMENT,
    level_code TINYINT NOT NULL,
    level_name VARCHAR(32) NOT NULL,
    fee_rate DECIMAL(7,6) NOT NULL,
    description VARCHAR(255) NULL DEFAULT NULL,
    is_enabled TINYINT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uq_merchant_levels_level_code (level_code),
    CONSTRAINT ck_merchant_levels_level_code CHECK (level_code BETWEEN 1 AND 5),
    CONSTRAINT ck_merchant_levels_fee_rate CHECK (
        (level_code = 1 AND fee_rate = 0.001000)
        OR (level_code = 2 AND fee_rate = 0.002000)
        OR (level_code = 3 AND fee_rate = 0.005000)
        OR (level_code = 4 AND fee_rate = 0.007500)
        OR (level_code = 5 AND fee_rate = 0.010000)
    ),
    CONSTRAINT ck_merchant_levels_is_enabled CHECK (is_enabled IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE categories (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL,
    description VARCHAR(255) NULL DEFAULT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    is_enabled TINYINT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uq_categories_name (name),
    CONSTRAINT ck_categories_sort_order CHECK (sort_order >= 0),
    CONSTRAINT ck_categories_is_enabled CHECK (is_enabled IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE merchants (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    shop_name VARCHAR(100) NOT NULL,
    shop_description VARCHAR(500) NULL DEFAULT NULL,
    business_license_ref VARCHAR(512) NOT NULL,
    identity_document_ref VARCHAR(512) NOT NULL,
    review_status VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'PENDING',
    level_id BIGINT NULL DEFAULT NULL,
    operation_status VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'INACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_merchants_user_id (user_id),
    KEY ix_merchants_level_id (level_id),
    KEY ix_merchants_review_queue (review_status, created_at, id),
    CONSTRAINT fk_merchants_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_merchants_level FOREIGN KEY (level_id) REFERENCES merchant_levels (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_merchants_review_status CHECK (review_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_merchants_operation_status CHECK (operation_status IN ('INACTIVE', 'ACTIVE', 'SUSPENDED')),
    CONSTRAINT ck_merchants_active_review CHECK (operation_status <> 'ACTIVE' OR review_status = 'APPROVED')
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE products (
    id BIGINT NOT NULL AUTO_INCREMENT,
    merchant_id BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    original_price DECIMAL(14,2) NOT NULL,
    discount_price DECIMAL(14,2) NULL DEFAULT NULL,
    size_description VARCHAR(100) NULL DEFAULT NULL,
    usage_instructions TEXT NULL,
    is_negotiable TINYINT NOT NULL DEFAULT 0,
    condition_code VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL,
    available_quantity INT NOT NULL DEFAULT 0,
    reserved_quantity INT NOT NULL DEFAULT 0,
    review_status VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'DRAFT',
    listing_status VARCHAR(16) COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'OFF_SHELF',
    first_listed_at DATETIME(3) NULL DEFAULT NULL,
    deleted_at DATETIME(3) NULL DEFAULT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_products_merchant_listing (merchant_id, listing_status, id),
    KEY ix_products_category_listing (category_id, listing_status, id),
    KEY ix_products_review_queue (review_status, created_at, id),
    CONSTRAINT fk_products_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES categories (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_products_original_price CHECK (original_price >= 0),
    CONSTRAINT ck_products_discount_price CHECK (discount_price IS NULL OR (discount_price >= 0 AND discount_price <= original_price)),
    CONSTRAINT ck_products_is_negotiable CHECK (is_negotiable IN (0, 1)),
    CONSTRAINT ck_products_available_quantity CHECK (available_quantity >= 0),
    CONSTRAINT ck_products_reserved_quantity CHECK (reserved_quantity >= 0),
    CONSTRAINT ck_products_review_status CHECK (review_status IN ('DRAFT', 'PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_products_listing_status CHECK (listing_status IN ('OFF_SHELF', 'ON_SHELF')),
    CONSTRAINT ck_products_on_shelf CHECK (
        listing_status <> 'ON_SHELF'
        OR (review_status = 'APPROVED' AND deleted_at IS NULL AND first_listed_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE product_images (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    storage_ref VARCHAR(512) NOT NULL,
    sort_order INT NOT NULL,
    is_primary TINYINT NOT NULL DEFAULT 0,
    primary_product_key BIGINT GENERATED ALWAYS AS (CASE WHEN is_primary = 1 THEN product_id ELSE NULL END) VIRTUAL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_product_images_product_sort (product_id, sort_order),
    UNIQUE KEY uq_product_images_primary (primary_product_key),
    CONSTRAINT fk_product_images_product FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_product_images_sort_order CHECK (sort_order >= 0),
    CONSTRAINT ck_product_images_is_primary CHECK (is_primary IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
