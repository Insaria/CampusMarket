package edu.bistu.campusmarket.mapper;

import edu.bistu.campusmarket.dto.CatalogDtos.Category;
import edu.bistu.campusmarket.dto.CatalogDtos.Product;
import edu.bistu.campusmarket.dto.CatalogDtos.Query;
import edu.bistu.campusmarket.dto.CatalogDtos.Shop;
import edu.bistu.campusmarket.dto.CatalogDtos.SoldProduct;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface CatalogMapper {
    String COLUMNS = """
            p.id, p.category_id, p.name, m.id AS seller_id, m.shop_name, p.original_price,
            COALESCE(p.discount_price, p.original_price) AS price, p.size_description, p.usage_instructions,
            p.is_negotiable AS negotiable, p.condition_code, p.available_quantity, m.operation_status
            """;
    String CATALOG_JOINS = """
            FROM products p
            JOIN categories c ON c.id = p.category_id AND c.is_enabled = 1
            JOIN merchants m ON m.id = p.merchant_id
            JOIN users u ON u.id = m.user_id
            LEFT JOIN merchant_levels l ON l.id = m.level_id
            """;
    String LISTED_PRODUCT = """
            WHERE p.review_status = 'APPROVED' AND p.listing_status = 'ON_SHELF' AND p.deleted_at IS NULL
                AND u.role_code = 'USER' AND u.registration_status = 'APPROVED' AND u.account_status = 'ENABLED'
                AND m.review_status = 'APPROVED'
                AND (m.seller_type = 'PERSONAL' OR (m.seller_type = 'BUSINESS' AND l.is_enabled = 1))
            """;
    String VISIBILITY = LISTED_PRODUCT + " AND m.operation_status = 'ACTIVE' ";
    String VISIBLE = CATALOG_JOINS + VISIBILITY;
    String SALE_HISTORY = """
            JOIN (
                SELECT i.product_id, SUM(i.quantity - i.restocked_quantity) AS units_sold,
                       MAX(COALESCE(o.buyer_confirmed_at, o.auto_confirmed_at)) AS last_sale
                FROM order_items i JOIN merchant_orders o ON o.id = i.order_id
                WHERE o.order_status = 'COMPLETED' AND i.quantity > i.restocked_quantity
                GROUP BY i.product_id
            ) sales ON sales.product_id = p.id
            WHERE p.review_status = 'APPROVED' AND p.deleted_at IS NULL AND p.first_listed_at IS NOT NULL
                AND u.role_code = 'USER' AND u.registration_status = 'APPROVED' AND u.account_status = 'ENABLED'
                AND m.review_status = 'APPROVED' AND m.operation_status = 'ACTIVE'
                AND (m.seller_type = 'PERSONAL' OR (m.seller_type = 'BUSINESS' AND l.is_enabled = 1))
                AND (#{sellerId,jdbcType=BIGINT} IS NULL OR m.id = #{sellerId,jdbcType=BIGINT})
            """;
    String SORT_JOINS = """
            <choose>
                <when test="sort.name() == 'SALES_DESC'">
                    LEFT JOIN (
                        SELECT i.product_id, SUM(i.quantity - i.restocked_quantity) AS units_sold
                        FROM order_items i JOIN merchant_orders o ON o.id = i.order_id
                        WHERE o.order_status = 'COMPLETED'
                        GROUP BY i.product_id
                    ) sales ON sales.product_id = p.id
                </when>
                <when test="sort.name() == 'POSITIVE_RATE_DESC'">
                    LEFT JOIN (
                        SELECT i.product_id, CAST(
            """ + ReviewMapper.POSITIVE_COUNT + " AS DECIMAL(42,18)) / NULLIF(" + ReviewMapper.RATED_COUNT
            + ", 0) AS positive_rate " + ReviewMapper.PRODUCT_REVIEW_ROWS + """
                        GROUP BY i.product_id
                    ) ratings ON ratings.product_id = p.id
                </when>
            </choose>
            """;
    String FILTERS = """
            <if test="keyword != null">AND p.name LIKE #{keyword} ESCAPE '!'</if>
            <if test="categoryId != null">AND p.category_id = #{categoryId}</if>
            <if test="sellerId != null">AND p.merchant_id = #{sellerId}</if>
            <if test="minPrice != null">AND COALESCE(p.discount_price, p.original_price) &gt;= #{minPrice}</if>
            <if test="maxPrice != null">AND COALESCE(p.discount_price, p.original_price) &lt;= #{maxPrice}</if>
            """;

    @Select("SELECT id, name, description FROM categories WHERE is_enabled = 1 ORDER BY sort_order, id")
    List<Category> categories();

    @Select("SELECT COUNT(*) " + CATALOG_JOINS + SALE_HISTORY)
    long soldCount(@Param("sellerId") Long sellerId);

    @Select("SELECT p.id, p.name, m.id AS seller_id, m.shop_name, sales.units_sold "
            + CATALOG_JOINS + SALE_HISTORY + " ORDER BY sales.last_sale DESC, p.id DESC LIMIT #{size} OFFSET #{offset}")
    List<SoldProduct> soldPage(@Param("sellerId") Long sellerId, @Param("offset") long offset, @Param("size") int size);

    @Select("<script>SELECT COUNT(*) " + VISIBLE + FILTERS + "</script>")
    long count(Query query);

    @Select("<script>SELECT " + COLUMNS + CATALOG_JOINS + SORT_JOINS + VISIBILITY + FILTERS + """
            ORDER BY
            <choose>
                <when test="sort.name() == 'PRICE_ASC'">price ASC,</when>
                <when test="sort.name() == 'PRICE_DESC'">price DESC,</when>
                <when test="sort.name() == 'SALES_DESC'">COALESCE(sales.units_sold, 0) DESC,</when>
                <when test="sort.name() == 'POSITIVE_RATE_DESC'">COALESCE(ratings.positive_rate, 0) DESC,</when>
            </choose>
            p.first_listed_at DESC, p.id DESC
            LIMIT #{size} OFFSET #{offset}</script>
            """)
    List<Product> page(Query query);

    @Select("SELECT " + COLUMNS + VISIBLE + " AND p.id = #{id}")
    Product find(@Param("id") long id);

    @Select("SELECT EXISTS(SELECT 1 " + CATALOG_JOINS + LISTED_PRODUCT
            + " AND m.operation_status = 'SUSPENDED' AND p.id = #{id})")
    boolean suspendedProduct(@Param("id") long id);

    @Select("""
            SELECT m.id, m.shop_name, m.operation_status
            FROM merchants m JOIN users u ON u.id = m.user_id
            WHERE m.id = #{id} AND m.review_status = 'APPROVED' AND m.operation_status IN ('ACTIVE', 'SUSPENDED')
              AND u.role_code = 'USER' AND u.registration_status = 'APPROVED' AND u.account_status = 'ENABLED'
            """)
    Shop shop(@Param("id") long id);

    @Select("SELECT storage_ref FROM product_images WHERE product_id = #{id} AND sort_order = #{position}")
    String imageReference(@Param("id") long id, @Param("position") int position);

    @Select("SELECT sort_order FROM product_images WHERE product_id = #{id} ORDER BY is_primary DESC, sort_order")
    List<Integer> imagePositions(@Param("id") long id);
}
