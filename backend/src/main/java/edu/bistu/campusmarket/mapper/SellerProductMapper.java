package edu.bistu.campusmarket.mapper;

import edu.bistu.campusmarket.dto.SellerProductDtos.Draft;
import edu.bistu.campusmarket.dto.SellerProductDtos.Item;
import edu.bistu.campusmarket.dto.SellerProductDtos.ReviewSeller;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface SellerProductMapper {
    String COLUMNS = """
            id, category_id, name, original_price, discount_price, size_description, usage_instructions,
            is_negotiable AS negotiable, condition_code, available_quantity, reserved_quantity, review_status, listing_status
            """;

    @Select("""
            SELECT m.id FROM merchants m JOIN users u ON u.id = m.user_id
            LEFT JOIN merchant_levels l ON l.id = m.level_id
            WHERE u.id = #{userId} AND u.role_code = 'USER'
                AND u.registration_status = 'APPROVED' AND u.account_status = 'ENABLED'
                AND m.review_status = 'APPROVED' AND m.operation_status = 'ACTIVE'
                AND (m.seller_type = 'PERSONAL' OR (m.seller_type = 'BUSINESS' AND l.is_enabled = 1))
            FOR UPDATE
            """)
    Long lockEligibleSeller(@Param("userId") long userId);

    @Select("SELECT EXISTS (SELECT 1 FROM categories WHERE id = #{id} AND is_enabled = 1)")
    boolean categoryEnabled(@Param("id") long id);

    @Insert("""
            INSERT INTO products (merchant_id, category_id, name, original_price, discount_price,
                                  size_description, usage_instructions, is_negotiable, condition_code)
            VALUES (#{sellerId}, #{draft.categoryId}, #{draft.name}, #{draft.originalPrice}, #{draft.discountPrice},
                    #{draft.sizeDescription}, #{draft.usageInstructions}, #{draft.negotiable}, #{draft.conditionCode})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id")
    int create(@Param("sellerId") long sellerId, @Param("draft") Draft draft, @Param("key") GeneratedId key);

    @Update("""
            UPDATE products SET category_id = #{draft.categoryId}, name = #{draft.name},
                original_price = #{draft.originalPrice}, discount_price = #{draft.discountPrice},
                size_description = #{draft.sizeDescription}, usage_instructions = #{draft.usageInstructions},
                is_negotiable = #{draft.negotiable}, condition_code = #{draft.conditionCode},
                review_status = 'DRAFT', listing_status = 'OFF_SHELF'
            WHERE id = #{id} AND review_status IN ('DRAFT', 'REJECTED')
            """)
    int edit(@Param("id") long id, @Param("draft") Draft draft);

    @Select("SELECT " + COLUMNS + " FROM products WHERE id = #{id} AND merchant_id = #{sellerId} AND deleted_at IS NULL FOR UPDATE")
    Item lock(@Param("sellerId") long sellerId, @Param("id") long id);

    @Select("SELECT COUNT(*) FROM products WHERE merchant_id = #{sellerId} AND deleted_at IS NULL")
    long count(@Param("sellerId") long sellerId);

    @Select("SELECT " + COLUMNS + " FROM products WHERE merchant_id = #{sellerId} AND deleted_at IS NULL"
            + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}")
    List<Item> page(@Param("sellerId") long sellerId, @Param("size") int size, @Param("offset") long offset);

    @Select("""
            SELECT available_delta FROM inventory_events
            WHERE product_id = #{id} AND action_code = 'ADJUST' AND operation_key = #{key}
            """)
    Integer adjustment(@Param("id") long id, @Param("key") String key);

    @Update("UPDATE products SET available_quantity = available_quantity + #{delta} WHERE id = #{id}")
    int adjust(@Param("id") long id, @Param("delta") int delta);

    @Insert("""
            INSERT INTO inventory_events (product_id, action_code, operation_key, available_delta,
                                          reserved_delta, source_code, actor_user_id)
            VALUES (#{id}, 'ADJUST', #{key}, #{delta}, 0, 'SELLER', #{actor})
            """)
    int inventoryEvent(@Param("id") long id, @Param("key") String key, @Param("delta") int delta,
            @Param("actor") long actor);

    @Select("SELECT EXISTS (SELECT 1 FROM product_images WHERE product_id = #{id} AND is_primary = 1)")
    boolean hasPrimaryImage(@Param("id") long id);

    @Select("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM product_images WHERE product_id = #{id}")
    int nextImagePosition(@Param("id") long id);

    @Insert("""
            INSERT INTO product_images (product_id, storage_ref, sort_order, is_primary)
            VALUES (#{id}, #{reference}, #{position}, #{primary})
            """)
    int insertImage(@Param("id") long id, @Param("reference") String reference, @Param("position") int position,
            @Param("primary") boolean primary);

    @Update("UPDATE products SET review_status = 'PENDING' WHERE id = #{id} AND review_status = 'DRAFT'")
    int submit(@Param("id") long id);

    @Update("""
            UPDATE products SET listing_status = 'ON_SHELF',
                                first_listed_at = COALESCE(first_listed_at, CURRENT_TIMESTAMP(3))
            WHERE id = #{id} AND review_status = 'APPROVED' AND listing_status = 'OFF_SHELF'
            """)
    int publish(@Param("id") long id);

    @Update("UPDATE products SET listing_status = 'OFF_SHELF' WHERE id = #{id} AND listing_status = 'ON_SHELF'")
    int unpublish(@Param("id") long id);

    @Select("SELECT " + COLUMNS + " FROM products WHERE id = #{id} AND deleted_at IS NULL FOR UPDATE")
    Item lockForReview(@Param("id") long id);

    @Select("SELECT " + COLUMNS + " FROM products WHERE id = #{id} AND deleted_at IS NULL")
    Item findForReview(@Param("id") long id);

    @Select("""
            SELECT m.id AS seller_id, m.shop_name, m.seller_type
            FROM products p JOIN merchants m ON m.id = p.merchant_id WHERE p.id = #{id}
            """)
    ReviewSeller reviewSeller(@Param("id") long id);

    @Select("""
            SELECT sort_order FROM product_images WHERE product_id = #{id}
            ORDER BY is_primary DESC, sort_order
            """)
    List<Integer> reviewImagePositions(@Param("id") long id);

    @Select("SELECT storage_ref FROM product_images WHERE product_id = #{id} AND sort_order = #{position}")
    String reviewImage(@Param("id") long id, @Param("position") int position);

    @Select("""
            SELECT JSON_UNQUOTE(JSON_EXTRACT(details, '$.reason')) FROM audit_events
            WHERE target_type = 'PRODUCT' AND target_id = #{id} AND action_code IN ('PRODUCT_APPROVED', 'PRODUCT_REJECTED')
            ORDER BY created_at DESC, id DESC LIMIT 1
            """)
    String latestReviewReason(@Param("id") long id);

    @Update("UPDATE products SET review_status = #{decision} WHERE id = #{id} AND review_status = 'PENDING'")
    int review(@Param("id") long id, @Param("decision") String decision);

    String PENDING_PRODUCTS = " FROM products WHERE review_status = 'PENDING' AND deleted_at IS NULL";

    @Select("SELECT COUNT(*)" + PENDING_PRODUCTS)
    long countPendingReview();

    @Select("SELECT " + COLUMNS + PENDING_PRODUCTS
            + " ORDER BY created_at, id LIMIT #{size} OFFSET #{offset}")
    List<Item> pending(@Param("size") int size, @Param("offset") long offset);

    @Insert("""
            INSERT INTO audit_events (actor_type, actor_user_id, action_code, target_type, target_id)
            VALUES ('USER', #{actor}, #{action}, 'PRODUCT', #{id})
            """)
    int audit(@Param("actor") long actor, @Param("action") String action, @Param("id") long id);

    @Insert("""
            INSERT INTO audit_events (actor_type, actor_user_id, action_code, target_type, target_id, details)
            VALUES ('USER', #{actor}, #{action}, 'PRODUCT', #{id},
                CASE WHEN #{reason,jdbcType=VARCHAR} IS NULL THEN NULL ELSE JSON_OBJECT('reason', #{reason,jdbcType=VARCHAR}) END)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id", keyColumn = "id")
    int auditReview(@Param("actor") long actor, @Param("action") String action, @Param("id") long id,
            @Param("reason") String reason, @Param("key") GeneratedId key);

    class GeneratedId {
        private Long id;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }
    }
}
