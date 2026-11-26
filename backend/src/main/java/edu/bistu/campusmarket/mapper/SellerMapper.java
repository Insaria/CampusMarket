package edu.bistu.campusmarket.mapper;

import edu.bistu.campusmarket.dto.SellerDtos.SellerView;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SellerMapper {
    String CLOSURE_REASON = """
            CASE WHEN m.operation_status = 'SUSPENDED' THEN (
                SELECT JSON_UNQUOTE(JSON_EXTRACT(a.details, '$.reason')) FROM audit_events a
                WHERE a.target_type = 'MERCHANT' AND a.target_id = m.id AND a.action_code = 'SHOP_CLOSED'
                ORDER BY a.created_at DESC, a.id DESC LIMIT 1
            ) END AS closure_reason
            """;

    @Select("""
            SELECT id, role_code, registration_status, account_status,
                   CASE WHEN real_name IS NOT NULL AND CHAR_LENGTH(TRIM(real_name)) > 0
                        AND phone IS NOT NULL AND CHAR_LENGTH(TRIM(phone)) > 0
                        AND gender_code IS NOT NULL AND CHAR_LENGTH(TRIM(gender_code)) > 0
                        AND OCTET_LENGTH(bank_account_enc) > 0 THEN 1 ELSE 0 END AS profile_complete
            FROM users WHERE id = #{userId} FOR UPDATE
            """)
    Applicant lockApplicant(@Param("userId") long userId);

    @Select("""
            SELECT m.id AS seller_id, m.seller_type, m.shop_name, m.shop_description, m.review_status, m.operation_status,
                   l.level_code, l.level_name,
                   CASE WHEN m.seller_type = 'PERSONAL' THEN 0.000000 ELSE l.fee_rate END AS fee_rate,
            """ + CLOSURE_REASON + """
            FROM merchants m LEFT JOIN merchant_levels l ON l.id = m.level_id
            WHERE m.user_id = #{userId}
            """)
    SellerView findByUserId(@Param("userId") long userId);

    @Insert("""
            INSERT INTO merchants (user_id, seller_type, shop_name, shop_description, review_status, operation_status)
            VALUES (#{userId}, 'PERSONAL', #{shopName}, #{shopDescription}, 'APPROVED', 'ACTIVE')
            """)
    int insertPersonal(@Param("userId") long userId, @Param("shopName") String shopName,
            @Param("shopDescription") String shopDescription);

    @Insert("""
            INSERT INTO merchants (user_id, seller_type, shop_name, shop_description, business_license_ref,
                                   identity_document_ref, review_status, operation_status, level_id)
            VALUES (#{userId}, 'BUSINESS', #{shopName}, #{shopDescription}, #{licenseRef},
                    #{identityRef}, 'PENDING', 'INACTIVE', NULL)
            """)
    int insertBusiness(@Param("userId") long userId, @Param("shopName") String shopName,
            @Param("shopDescription") String shopDescription, @Param("licenseRef") String licenseRef,
            @Param("identityRef") String identityRef);

    record Applicant(Long id, String roleCode, String registrationStatus, String accountStatus, Integer profileComplete) {
    }
}
