package edu.bistu.campusmarket.mapper;

import edu.bistu.campusmarket.dto.AdminUserDtos.AccountChange;
import edu.bistu.campusmarket.dto.AdminUserDtos.AccountStatus;
import edu.bistu.campusmarket.dto.AdminUserDtos.ProfileUpdate;
import edu.bistu.campusmarket.dto.AdminUserDtos.RegistrationStatus;
import edu.bistu.campusmarket.dto.AdminUserDtos.ReviewDecision;
import edu.bistu.campusmarket.dto.AdminUserDtos.UserView;
import edu.bistu.campusmarket.mapper.SellerProductMapper.GeneratedId;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AdminUserMapper {
    String USER_COLUMNS = """
            id, login_name, real_name, phone, email, city, gender_code, role_code,
            registration_status, account_status, created_at, updated_at
            """;
    String FILTERS = """
            <where>
                <if test="pattern != null">login_name LIKE #{pattern} ESCAPE '!'</if>
                <if test="registration != null">AND registration_status = #{registration}</if>
                <if test="status != null">AND account_status = #{status}</if>
            </where>
            """;

    @Select("<script> SELECT COUNT(*) FROM users " + FILTERS + "</script>")
    long count(@Param("pattern") String pattern, @Param("registration") RegistrationStatus registration,
            @Param("status") AccountStatus status);

    @Select("""
            SELECT COUNT(*) FROM users
            WHERE role_code = 'USER' AND registration_status = 'PENDING' AND account_status <> 'DELETED'
            """)
    long countPendingReview();

    @Select("<script> SELECT " + USER_COLUMNS + " FROM users " + FILTERS
            + " ORDER BY created_at DESC, id DESC LIMIT #{size} OFFSET #{offset} </script>")
    List<UserView> findPage(@Param("pattern") String pattern, @Param("registration") RegistrationStatus registration,
            @Param("status") AccountStatus status, @Param("offset") long offset, @Param("size") int size);

    @Select("SELECT " + USER_COLUMNS + " FROM users WHERE id = #{id}")
    UserView findById(@Param("id") long id);

    @Select("SELECT " + USER_COLUMNS + " FROM users WHERE id = #{id} FOR UPDATE")
    UserView lockById(@Param("id") long id);

    @Update("""
            UPDATE users SET registration_status = #{decision}
            WHERE id = #{id} AND role_code = 'USER' AND registration_status = 'PENDING'
            """)
    int review(@Param("id") long id, @Param("decision") ReviewDecision decision);

    @Update("""
            UPDATE users SET account_status = #{change}
            WHERE id = #{id} AND account_status IN ('ENABLED', 'DISABLED')
            """)
    int changeStatus(@Param("id") long id, @Param("change") AccountChange change);

    @Update("""
            UPDATE users SET real_name = #{profile.realName}, phone = #{profile.phone},
                email = #{profile.email}, city = #{profile.city}, gender_code = #{profile.genderCode}
            WHERE id = #{id} AND role_code = 'USER' AND account_status <> 'DELETED'
            """)
    int updateProfile(@Param("id") long id, @Param("profile") ProfileUpdate profile);

    @Update("""
            UPDATE users u SET account_status = 'DELETED'
            WHERE u.id = #{id} AND u.role_code = 'USER' AND u.account_status = 'DISABLED'
                AND NOT EXISTS (SELECT 1 FROM merchants m WHERE m.user_id = u.id)
                AND NOT EXISTS (SELECT 1 FROM checkout_batches b WHERE b.buyer_id = u.id)
                AND NOT EXISTS (SELECT 1 FROM wallets w WHERE w.user_id = u.id AND w.balance <> 0)
                AND NOT EXISTS (SELECT 1 FROM points_accounts a WHERE a.user_id = u.id AND a.balance <> 0)
            """)
    int deleteUnusedAccount(@Param("id") long id);

    @Insert("""
            INSERT INTO audit_events (actor_type, actor_user_id, system_source, action_code, target_type, target_id)
            VALUES ('USER', #{actorId}, NULL, #{action}, 'USER', #{targetId})
            """)
    int audit(@Param("actorId") long actorId, @Param("action") String action, @Param("targetId") long targetId);

    @Insert("INSERT INTO wallets (user_id) VALUES (#{id}) ON DUPLICATE KEY UPDATE user_id = wallets.user_id")
    void ensureWallet(@Param("id") long userId);

    @Select("SELECT id, balance FROM wallets WHERE user_id = #{id} FOR UPDATE")
    WalletPaymentMapper.Wallet lockWallet(@Param("id") long userId);

    @Select("""
            SELECT id, amount FROM wallet_entries WHERE wallet_id = #{walletId}
                AND entry_kind = 'ADMIN_RECHARGE' AND operation_key = #{key} FOR UPDATE
            """)
    RechargeEntry rechargeEntry(@Param("walletId") long walletId, @Param("key") String requestKey);

    @Update("""
            UPDATE wallets SET balance = balance + #{amount}
            WHERE id = #{walletId} AND balance <= 999999999999.99 - #{amount}
            """)
    int credit(@Param("walletId") long walletId, @Param("amount") BigDecimal amount);

    @Insert("""
            INSERT INTO wallet_entries (wallet_id, entry_kind, direction, amount, operation_key)
            VALUES (#{walletId}, 'ADMIN_RECHARGE', 'CREDIT', #{amount}, #{requestKey})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id", keyColumn = "id")
    int recordRecharge(@Param("walletId") long walletId, @Param("amount") BigDecimal amount,
            @Param("requestKey") String requestKey, @Param("key") GeneratedId key);

    @Insert("""
            INSERT INTO audit_events (actor_type, actor_user_id, action_code, target_type, target_id, details)
            VALUES ('USER', #{actor}, 'WALLET_RECHARGED', 'USER', #{user},
                JSON_OBJECT('walletEntryId', #{entry}, 'amount', #{amount}, 'reason', #{reason}))
            """)
    int auditRecharge(@Param("actor") long actor, @Param("user") long user, @Param("entry") long entry,
            @Param("amount") BigDecimal amount, @Param("reason") String reason);

    record RechargeEntry(Long id, BigDecimal amount) {
    }
}
