package edu.bistu.campusmarket.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AccountAccessMapper {
    @Select("""
            SELECT u.id, u.role_code, u.registration_status, u.account_status,
                   m.id AS merchant_id, m.review_status, m.operation_status, l.is_enabled AS level_enabled,
                   m.seller_type
            FROM users u
            LEFT JOIN merchants m ON m.user_id = u.id
            LEFT JOIN merchant_levels l ON l.id = m.level_id
            WHERE u.id = #{userId}
            """)
    AccountAccess findByUserId(@Param("userId") Long userId);

    record AccountAccess(Long id, String roleCode, String registrationStatus, String accountStatus,
            Long merchantId, String reviewStatus, String operationStatus, Integer levelEnabled, String sellerType) {
    }
}
