package edu.bistu.campusmarket.mapper;

import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface CartMapper {
    @Select("""
            SELECT p.id AS product_id, p.name, m.id AS seller_id, m.shop_name, i.quantity,
                   p.available_quantity, COALESCE(p.discount_price, p.original_price) AS price
            FROM carts c
            JOIN cart_items i ON i.cart_id = c.id
            JOIN products p ON p.id = i.product_id
            JOIN merchants m ON m.id = p.merchant_id
            WHERE c.user_id = #{userId}
            ORDER BY m.id, i.id
            """)
    List<Row> list(@Param("userId") long userId);

    @Insert("INSERT INTO carts (user_id) VALUES (#{userId}) ON DUPLICATE KEY UPDATE user_id = user_id")
    void ensureCart(@Param("userId") long userId);

    @Select("SELECT id FROM carts WHERE user_id = #{userId} FOR UPDATE")
    long lock(@Param("userId") long userId);

    @Insert("""
            INSERT INTO cart_items (cart_id, product_id, quantity) VALUES (#{cartId}, #{productId}, #{quantity})
            ON DUPLICATE KEY UPDATE quantity = #{quantity}
            """)
    void put(@Param("cartId") long cartId, @Param("productId") long productId, @Param("quantity") int quantity);

    @Delete("""
            DELETE FROM cart_items WHERE product_id = #{productId}
                AND cart_id = (SELECT id FROM carts WHERE user_id = #{userId})
            """)
    void remove(@Param("userId") long userId, @Param("productId") long productId);

    record Row(Long productId, String name, Long sellerId, String shopName, Integer quantity,
            Integer availableQuantity, BigDecimal price) {
    }
}
