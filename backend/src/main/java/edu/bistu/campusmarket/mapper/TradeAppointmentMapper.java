package edu.bistu.campusmarket.mapper;

import edu.bistu.campusmarket.dto.TradeAppointmentDtos.Pending;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface TradeAppointmentMapper {
    @Select("""
            SELECT id, actor_user_id,
                   CAST(JSON_UNQUOTE(JSON_EXTRACT(details, '$.scheduledTradeAt')) AS DATETIME(3)) AS scheduled_trade_at,
                   JSON_UNQUOTE(JSON_EXTRACT(details, '$.location')) AS location
            FROM audit_events WHERE target_type = 'ORDER' AND target_id = #{orderId}
              AND action_code IN ('TRADE_APPOINTMENT_PROPOSED', 'TRADE_APPOINTMENT_ACCEPTED')
            ORDER BY id DESC LIMIT 1
            """)
    Pending latest(@Param("orderId") long orderId);

    @Select("SELECT UTC_TIMESTAMP(3)")
    LocalDateTime now();

    @Insert("""
            INSERT INTO audit_events (actor_type, actor_user_id, action_code, target_type, target_id, details)
            VALUES ('USER', #{actor}, 'TRADE_APPOINTMENT_PROPOSED', 'ORDER', #{orderId},
                JSON_OBJECT('scheduledTradeAt', DATE_FORMAT(#{time}, '%Y-%m-%d %H:%i:%s.%f'), 'location', #{location}))
            """)
    int propose(@Param("actor") long actor, @Param("orderId") long orderId,
            @Param("time") LocalDateTime time, @Param("location") String location);

    @Update("""
            UPDATE merchant_orders SET scheduled_trade_at = #{time}, trade_location = #{location}
            WHERE id = #{orderId} AND order_status = 'PAID'
            """)
    int confirm(@Param("orderId") long orderId, @Param("time") LocalDateTime time, @Param("location") String location);

    @Insert("""
            INSERT INTO audit_events (actor_type, actor_user_id, action_code, target_type, target_id, details)
            VALUES ('USER', #{actor}, 'TRADE_APPOINTMENT_ACCEPTED', 'ORDER', #{orderId},
                JSON_OBJECT('proposalId', #{proposalId}))
            """)
    int accept(@Param("actor") long actor, @Param("orderId") long orderId, @Param("proposalId") long proposalId);
}
