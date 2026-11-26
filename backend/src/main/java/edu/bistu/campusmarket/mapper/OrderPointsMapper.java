package edu.bistu.campusmarket.mapper;

import edu.bistu.campusmarket.dto.AccountFinanceDtos.PointsEntry;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface OrderPointsMapper {
    String REDEMPTION_KIND = "ORDER_POINTS_REDEMPTION";
    String REWARD_KIND = "ORDER_POINTS_REWARD";

    @Select("SELECT id, balance FROM points_accounts WHERE user_id = #{userId}")
    Account findAccount(@Param("userId") long userId);

    @Select("SELECT COUNT(*) FROM points_entries WHERE account_id = #{accountId}")
    long countEntries(@Param("accountId") long accountId);

    @Select("""
            SELECT e.id, e.order_id, o.order_no, e.entry_kind, e.direction, e.quantity, e.created_at
            FROM points_entries e JOIN merchant_orders o ON o.id = e.order_id
            WHERE e.account_id = #{accountId}
            ORDER BY e.created_at DESC, e.id DESC LIMIT #{size} OFFSET #{offset}
            """)
    List<PointsEntry> entries(@Param("accountId") long accountId, @Param("size") int size, @Param("offset") long offset);

    @Select("SELECT id, balance FROM points_accounts WHERE user_id = #{buyerId} FOR UPDATE")
    Account lockAccount(@Param("buyerId") long buyerId);

    @Insert("INSERT INTO points_accounts (user_id) VALUES (#{buyerId})")
    int createAccount(@Param("buyerId") long buyerId);

    @Update("UPDATE points_accounts SET balance = balance - #{points} WHERE id = #{id} AND balance >= #{points}")
    int debit(@Param("id") long id, @Param("points") long points);

    @Insert("INSERT INTO points_entries (account_id, order_id, entry_kind, direction, quantity, operation_key) "
            + "VALUES (#{accountId}, #{orderId}, '" + REDEMPTION_KIND + "', 'DEBIT', #{points}, "
            + "CONCAT('" + REDEMPTION_KIND + ":', #{orderId}))")
    int recordRedemption(@Param("accountId") long accountId, @Param("orderId") long orderId, @Param("points") long points);

    @Update("""
            UPDATE points_accounts SET balance = balance + #{points}
            WHERE id = #{id} AND balance <= 9223372036854775807 - #{points}
            """)
    int creditReward(@Param("id") long id, @Param("points") long points);

    @Insert("INSERT INTO points_entries (account_id, order_id, entry_kind, direction, quantity, operation_key) "
            + "VALUES (#{accountId}, #{orderId}, '" + REWARD_KIND + "', 'CREDIT', #{points}, "
            + "CONCAT('" + REWARD_KIND + ":', #{orderId}))")
    int recordReward(@Param("accountId") long accountId, @Param("orderId") long orderId, @Param("points") long points);

    record Account(Long id, Long balance) {
    }
}
