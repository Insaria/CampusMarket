package edu.bistu.campusmarket.mapper;

import edu.bistu.campusmarket.dto.AdminSellerDtos.Level;
import edu.bistu.campusmarket.dto.AdminSellerDtos.ReviewStatus;
import edu.bistu.campusmarket.dto.AdminSellerDtos.Seller;
import edu.bistu.campusmarket.mapper.SellerProductMapper.GeneratedId;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AdminSellerMapper {
    String FILTER = "WHERE (#{includePersonal} OR m.seller_type = 'BUSINESS') AND (#{status} IS NULL OR m.review_status = #{status})";
    String VIEW = """
            SELECT m.id, m.user_id, m.shop_name, m.shop_description, m.review_status, m.operation_status, l.level_code,
                   (SELECT JSON_UNQUOTE(JSON_EXTRACT(a.details, '$.reason')) FROM audit_events a
                    WHERE a.target_type = 'MERCHANT' AND a.target_id = m.id
                        AND a.action_code IN ('SELLER_APPROVED', 'SELLER_REJECTED')
                    ORDER BY a.created_at DESC, a.id DESC LIMIT 1) AS review_reason, m.seller_type,
            """ + SellerMapper.CLOSURE_REASON + """
            FROM merchants m LEFT JOIN merchant_levels l ON l.id = m.level_id
            """;

    @Select("SELECT COUNT(*) FROM merchants m " + FILTER)
    long count(@Param("status") ReviewStatus status, @Param("includePersonal") boolean includePersonal);

    @Select(VIEW + FILTER + " ORDER BY m.created_at DESC, m.id DESC LIMIT #{size} OFFSET #{offset}")
    List<Seller> page(@Param("status") ReviewStatus status, @Param("includePersonal") boolean includePersonal,
            @Param("size") int size, @Param("offset") long offset);

    @Select(VIEW + " WHERE m.id = #{id} AND m.seller_type = 'BUSINESS'")
    Seller find(@Param("id") long id);

    @Select(VIEW + " WHERE m.id = #{id}")
    Seller findShop(@Param("id") long id);

    @Select("SELECT user_id, review_status, operation_status FROM merchants WHERE id = #{id} FOR UPDATE")
    Shop lockShop(@Param("id") long id);

    @Update("UPDATE merchants SET operation_status = #{status} WHERE id = #{id}")
    int changeOperation(@Param("id") long id, @Param("status") String status);

    @Insert("""
            INSERT INTO audit_events (actor_type, actor_user_id, action_code, target_type, target_id, details)
            VALUES ('USER', #{actorId}, #{action}, 'MERCHANT', #{targetId},
                JSON_OBJECT('reason', #{reason}, 'previousStatus', #{previousStatus}))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id", keyColumn = "id")
    int auditOperation(@Param("actorId") long actorId, @Param("targetId") long id, @Param("action") String action,
            @Param("reason") String reason, @Param("previousStatus") String previousStatus, @Param("key") GeneratedId key);

    record Shop(long userId, String reviewStatus, String operationStatus) {}

    @Select("""
            SELECT id, review_status, level_id,
                   IF(CHAR_LENGTH(TRIM(business_license_ref)) > 0 AND CHAR_LENGTH(TRIM(identity_document_ref)) > 0, 1, 0) AS credentials_present
            FROM merchants WHERE id = #{id} AND seller_type = 'BUSINESS' FOR UPDATE
            """)
    LockedSeller lock(@Param("id") long id);

    @Select("""
            SELECT CASE WHEN #{license} THEN business_license_ref ELSE identity_document_ref END
            FROM merchants WHERE id = #{id} AND seller_type = 'BUSINESS'
            """)
    String document(@Param("id") long id, @Param("license") boolean license);

    @Select("SELECT id, level_code, level_name, fee_rate, is_enabled AS enabled FROM merchant_levels ORDER BY level_code")
    List<Level> levels();

    @Select("SELECT id FROM merchant_levels WHERE level_code = #{code} AND is_enabled = 1 FOR UPDATE")
    Long lockEnabledLevel(@Param("code") int code);

    @Update("""
            UPDATE merchants SET review_status = #{decision}, operation_status = #{operation}, level_id = #{levelId}
            WHERE id = #{id} AND seller_type = 'BUSINESS' AND review_status = 'PENDING'
            """)
    int review(@Param("id") long id, @Param("decision") String decision, @Param("operation") String operation,
            @Param("levelId") Long levelId);

    @Update("""
            UPDATE merchants SET level_id = #{levelId}
            WHERE id = #{id} AND seller_type = 'BUSINESS' AND review_status = 'APPROVED'
            """)
    int changeLevel(@Param("id") long id, @Param("levelId") long levelId);

    @Insert("""
            INSERT INTO audit_events (actor_type, actor_user_id, action_code, target_type, target_id)
            VALUES ('USER', #{actorId}, #{action}, 'MERCHANT', #{targetId})
            """)
    int audit(@Param("actorId") long actorId, @Param("action") String action, @Param("targetId") long targetId);

    @Insert("""
            INSERT INTO audit_events (actor_type, actor_user_id, action_code, target_type, target_id, details)
            VALUES ('USER', #{actorId}, 'SELLER_REJECTED', 'MERCHANT', #{targetId}, JSON_OBJECT('reason', #{reason}))
            """)
    int auditRejection(@Param("actorId") long actorId, @Param("targetId") long targetId, @Param("reason") String reason);

    @Insert("""
            INSERT INTO audit_events (actor_type, actor_user_id, action_code, target_type, target_id, details)
            VALUES ('USER', #{actorId}, #{action}, 'MERCHANT', #{targetId},
                JSON_OBJECT('oldLevelId', #{oldLevelId,jdbcType=BIGINT},
                    'oldLevelCode', (SELECT level_code FROM merchant_levels WHERE id = #{oldLevelId,jdbcType=BIGINT}),
                    'newLevelId', #{newLevelId},
                    'newLevelCode', #{newLevelCode}, 'reason', #{reason}))
            """)
    int auditLevel(@Param("actorId") long actorId, @Param("action") String action, @Param("targetId") long targetId,
            @Param("oldLevelId") Long oldLevelId, @Param("newLevelId") long newLevelId,
            @Param("newLevelCode") int newLevelCode, @Param("reason") String reason);

    record LockedSeller(Long id, String reviewStatus, Long levelId, Integer credentialsPresent) {
    }
}
