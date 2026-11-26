package edu.bistu.campusmarket.mapper;

import edu.bistu.campusmarket.dto.NotificationDtos.Message;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface NotificationMapper {
    String OWNED = " FROM notifications WHERE recipient_id = #{recipientId} ";
    String COLUMNS = "SELECT id, notification_kind, target_type, target_id, title, content, read_at, created_at ";
    String FILTER = " AND (#{unreadOnly} = FALSE OR read_at IS NULL) ";

    @Select("SELECT COUNT(*) " + OWNED + FILTER)
    long count(@Param("recipientId") long recipientId, @Param("unreadOnly") boolean unreadOnly);

    @Select(COLUMNS + OWNED + FILTER + " ORDER BY created_at DESC, id DESC LIMIT #{size} OFFSET #{offset}")
    List<Message> page(@Param("recipientId") long recipientId, @Param("unreadOnly") boolean unreadOnly,
            @Param("size") int size, @Param("offset") long offset);

    @Select(COLUMNS + OWNED + " AND id = #{id}")
    Message find(@Param("recipientId") long recipientId, @Param("id") long id);

    @Update("""
            UPDATE notifications SET read_at = UTC_TIMESTAMP(3)
            WHERE recipient_id = #{recipientId} AND id = #{id} AND read_at IS NULL
            """)
    int read(@Param("recipientId") long recipientId, @Param("id") long id);

    @Update("UPDATE notifications SET read_at = UTC_TIMESTAMP(3) WHERE recipient_id = #{recipientId} AND read_at IS NULL")
    int readAll(@Param("recipientId") long recipientId);

    @Insert("""
            INSERT INTO notifications (recipient_id, notification_kind, event_key, target_type, target_id, title, content)
            SELECT CASE WHEN #{seller} THEN m.user_id ELSE b.buyer_id END, #{kind}, #{eventKey},
                   #{targetType}, #{targetId}, #{title}, #{content}
            FROM merchant_orders o JOIN checkout_batches b ON b.id = o.batch_id
            JOIN merchants m ON m.id = o.merchant_id WHERE o.id = #{orderId}
            ON DUPLICATE KEY UPDATE read_at = notifications.read_at
            """)
    int record(@Param("orderId") long orderId, @Param("seller") boolean seller, @Param("kind") String kind,
            @Param("eventKey") String eventKey, @Param("targetType") String targetType, @Param("targetId") long targetId,
            @Param("title") String title, @Param("content") String content);

    @Insert("""
            INSERT INTO notifications (recipient_id, notification_kind, event_key, target_type, target_id, title, content, created_at)
            SELECT m.user_id, a.action_code, CONCAT('PRODUCT_REVIEW:', a.id), 'PRODUCT', p.id, #{title},
                   CONCAT(#{content}, ' 审核时间：', DATE_FORMAT(a.created_at, '%Y-%m-%d %H:%i:%s'), ' UTC。'),
                   a.created_at
            FROM audit_events a
            JOIN products p ON a.target_id = p.id
            JOIN merchants m ON m.id = p.merchant_id
            WHERE a.id = #{auditId} AND a.target_type = 'PRODUCT'
                AND a.action_code IN ('PRODUCT_APPROVED', 'PRODUCT_REJECTED')
            """)
    int recordProductReview(@Param("auditId") long auditId, @Param("title") String title, @Param("content") String content);

    @Insert("""
            INSERT INTO notifications (recipient_id, notification_kind, event_key, target_type, target_id, title, content)
            VALUES (#{recipient}, 'PRODUCT_MESSAGE', CONCAT('PRODUCT_MESSAGE:', #{id}), 'PRODUCT_MESSAGE', #{id},
                '收到商品留言', '请打开会话查看留言或回复。')
            ON DUPLICATE KEY UPDATE read_at = notifications.read_at
            """)
    int recordProductMessage(@Param("recipient") long recipient, @Param("id") long id);

    @Insert("""
            INSERT INTO notifications (recipient_id, notification_kind, event_key, target_type, target_id, title, content)
            VALUES (#{recipient}, #{kind}, CONCAT(#{kind}, ':', #{auditId}), 'MERCHANT', #{shopId}, #{title}, #{content})
            """)
    int recordShopOperation(@Param("recipient") long recipient, @Param("shopId") long shopId,
            @Param("auditId") long auditId, @Param("kind") String kind, @Param("title") String title,
            @Param("content") String content);
}
