package edu.bistu.campusmarket.mapper;

import edu.bistu.campusmarket.dto.CatalogDtos.Product;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface FavoriteMapper {
    String OWNED = " AND EXISTS (SELECT 1 FROM favorites f WHERE f.user_id = #{userId} AND f.product_id = p.id)";
    String FAVORITE_PRODUCTS = CatalogMapper.CATALOG_JOINS + CatalogMapper.LISTED_PRODUCT
            + " AND m.operation_status IN ('ACTIVE', 'SUSPENDED') " + OWNED;

    @Select("SELECT COUNT(*) " + FAVORITE_PRODUCTS)
    long count(@Param("userId") long userId);

    @Select("SELECT " + CatalogMapper.COLUMNS + FAVORITE_PRODUCTS
            + " ORDER BY p.id DESC LIMIT #{size} OFFSET #{offset}")
    List<Product> page(@Param("userId") long userId, @Param("size") int size, @Param("offset") long offset);

    @Insert("""
            INSERT INTO favorites (user_id, product_id) VALUES (#{userId}, #{productId})
            ON DUPLICATE KEY UPDATE created_at = created_at
            """)
    void add(@Param("userId") long userId, @Param("productId") long productId);

    @Delete("DELETE FROM favorites WHERE user_id = #{userId} AND product_id = #{productId}")
    void remove(@Param("userId") long userId, @Param("productId") long productId);
}
