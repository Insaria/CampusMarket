package edu.bistu.campusmarket.mapper;

import edu.bistu.campusmarket.dto.ReviewDtos.ProductReview;
import edu.bistu.campusmarket.dto.ReviewDtos.TradeReview;
import edu.bistu.campusmarket.dto.ReviewDtos.TradeHistoryItem;
import edu.bistu.campusmarket.mapper.SellerProductMapper.GeneratedId;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ReviewMapper {
    String RATED_COUNT = "COUNT(rating)";
    String POSITIVE_COUNT = "COUNT(CASE WHEN rating BETWEEN 4 AND 5 THEN 1 END)";
    String STATS = "COUNT(*) AS review_count, " + RATED_COUNT + " AS rated_count, "
            + POSITIVE_COUNT + " AS positive_count, AVG(rating) AS average_rating ";
    String PRODUCT_REVIEW_ROWS = """
            FROM order_items i JOIN product_reviews r ON r.order_item_id = i.id
            """;
    String TRADE_PARTIES = """
            FROM trade_reviews t
            JOIN merchant_orders o ON o.id = t.order_id
            JOIN checkout_batches b ON b.id = o.batch_id
            JOIN merchants m ON m.id = o.merchant_id
            """;
    String HISTORY_OWNER = """
            WHERE (t.reviewer_id = #{userId} OR t.reviewee_id = #{userId})
                AND ((t.reviewer_id = b.buyer_id AND t.reviewee_id = m.user_id)
                    OR (t.reviewer_id = m.user_id AND t.reviewee_id = b.buyer_id))
            """;

    @Select("SELECT COUNT(*) " + TRADE_PARTIES + HISTORY_OWNER)
    long countHistory(@Param("userId") long userId);

    @Select("""
            SELECT t.id, t.order_id, o.order_no, t.reviewer_id, t.reviewee_id, t.return_request_id,
                   t.scene_key, t.rating, t.content, t.created_at
            """ + TRADE_PARTIES + HISTORY_OWNER + " ORDER BY t.created_at DESC, t.id DESC LIMIT #{size} OFFSET #{offset}")
    List<TradeHistoryItem> history(@Param("userId") long userId, @Param("size") int size, @Param("offset") long offset);

    @Select("SELECT EXISTS (SELECT 1 FROM merchants WHERE user_id = #{userId})")
    boolean hasSellerProfile(@Param("userId") long userId);

    @Select("""
            SELECT i.order_id, p.merchant_id FROM order_items i JOIN products p ON p.id = i.product_id
            WHERE i.id = #{itemId}
            """)
    PurchasedItem findItem(@Param("itemId") long itemId);

    @Select("""
            SELECT b.buyer_id, m.user_id AS seller_user_id
            FROM merchant_orders o
            JOIN checkout_batches b ON b.id = o.batch_id
            JOIN merchants m ON m.id = o.merchant_id
            WHERE o.id = #{orderId}
            """)
    Parties findParties(@Param("orderId") long orderId);

    @Insert("""
            INSERT INTO product_reviews (order_item_id, rating, content)
            VALUES (#{itemId}, #{rating}, #{content})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id", keyColumn = "id")
    int insertProductReview(@Param("itemId") long itemId, @Param("rating") int rating,
            @Param("content") String content, @Param("key") GeneratedId key);

    @Insert("""
            INSERT INTO trade_reviews (order_id, reviewer_id, reviewee_id, return_request_id, rating, content)
            VALUES (#{orderId}, #{reviewerId}, #{revieweeId}, #{returnRequestId,jdbcType=BIGINT}, #{rating}, #{content})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id", keyColumn = "id")
    int insertTradeReview(@Param("orderId") long orderId, @Param("reviewerId") long reviewerId,
            @Param("revieweeId") long revieweeId, @Param("returnRequestId") Long returnRequestId,
            @Param("rating") int rating, @Param("content") String content, @Param("key") GeneratedId key);

    @Select("SELECT r.id, r.rating, r.content, r.created_at " + PRODUCT_REVIEW_ROWS + """
            WHERE i.product_id = #{productId} ORDER BY r.created_at DESC, r.id DESC LIMIT #{size} OFFSET #{offset}
            """)
    List<ProductReview> findProductReviews(@Param("productId") long productId, @Param("size") int size,
            @Param("offset") long offset);

    @Select("SELECT " + STATS + PRODUCT_REVIEW_ROWS + " WHERE i.product_id = #{productId}")
    RatingAggregate productRatings(@Param("productId") long productId);

    @Select("SELECT " + STATS + TRADE_PARTIES + """
            WHERE t.reviewee_id = #{sellerId} AND m.user_id = t.reviewee_id
                AND b.buyer_id = t.reviewer_id AND t.return_request_id IS NULL
            """)
    RatingAggregate sellerRatings(@Param("sellerId") long sellerId);

    @Select("SELECT " + STATS + TRADE_PARTIES + """
            WHERE t.reviewee_id = #{buyerId} AND b.buyer_id = t.reviewee_id
                AND m.user_id = t.reviewer_id
            """)
    RatingAggregate buyerRatings(@Param("buyerId") long buyerId);

    @Select("""
            SELECT id, reviewer_id, reviewee_id, return_request_id, scene_key, rating, content, created_at
            FROM trade_reviews WHERE order_id = #{orderId}
                AND ((reviewer_id = #{buyerId} AND reviewee_id = #{sellerId})
                    OR (reviewer_id = #{sellerId} AND reviewee_id = #{buyerId}))
            ORDER BY created_at DESC, id DESC
            """)
    List<TradeReview> findTradeReviews(@Param("orderId") long orderId, @Param("buyerId") long buyerId,
            @Param("sellerId") long sellerId);

    record PurchasedItem(Long orderId, Long merchantId) {
    }

    record Parties(Long buyerId, Long sellerUserId) {
    }

    record RatingAggregate(Long reviewCount, Long ratedCount, Long positiveCount, BigDecimal averageRating) {
    }
}
