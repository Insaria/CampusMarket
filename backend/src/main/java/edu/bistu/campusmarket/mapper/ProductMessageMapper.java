package edu.bistu.campusmarket.mapper;

import edu.bistu.campusmarket.mapper.SellerProductMapper.GeneratedId;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ProductMessageMapper {
    @Select("SELECT m.user_id " + CatalogMapper.VISIBLE + " AND p.id = #{productId}")
    Long visibleSeller(@Param("productId") long productId);

    @Select("""
            SELECT product_id, buyer_id, seller_id FROM product_messages
            WHERE id = #{id} AND #{actor} IN (buyer_id, seller_id)
            """)
    Conversation owned(@Param("id") long id, @Param("actor") long actor);

    @Select("""
            SELECT id, sender_id, content, created_at FROM product_messages
            WHERE product_id = #{thread.productId} AND buyer_id = #{thread.buyerId}
              AND seller_id = #{thread.sellerId}
              AND (#{before,jdbcType=BIGINT} IS NULL OR id < #{before,jdbcType=BIGINT})
            ORDER BY id DESC LIMIT #{size}
            """)
    List<Message> page(@Param("thread") Conversation thread, @Param("before") Long before, @Param("size") int size);

    @Insert("""
            INSERT INTO product_messages (product_id, buyer_id, seller_id, sender_id, content)
            VALUES (#{thread.productId}, #{thread.buyerId}, #{thread.sellerId}, #{sender}, #{content})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id", keyColumn = "id")
    int insert(@Param("thread") Conversation thread, @Param("sender") long sender, @Param("content") String content,
            @Param("key") GeneratedId key);

    record Conversation(long productId, long buyerId, long sellerId) {}
    record Message(long id, long senderId, String content, LocalDateTime createdAt) {}
}
