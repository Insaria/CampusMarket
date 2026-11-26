USE `2024011324`;

CREATE TABLE product_messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    buyer_id BIGINT NOT NULL,
    seller_id BIGINT NOT NULL,
    sender_id BIGINT NOT NULL,
    content VARCHAR(1000) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_product_messages_thread (product_id, buyer_id, seller_id, id),
    KEY ix_product_messages_buyer (buyer_id),
    KEY ix_product_messages_seller (seller_id),
    KEY ix_product_messages_sender (sender_id),
    CONSTRAINT fk_product_messages_product FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE RESTRICT,
    CONSTRAINT fk_product_messages_buyer FOREIGN KEY (buyer_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_product_messages_seller FOREIGN KEY (seller_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_product_messages_sender FOREIGN KEY (sender_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_product_messages_participants CHECK (buyer_id <> seller_id AND sender_id IN (buyer_id, seller_id)),
    CONSTRAINT ck_product_messages_content CHECK (CHAR_LENGTH(TRIM(content)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
