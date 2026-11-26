package edu.bistu.campusmarket.mapper;

import edu.bistu.campusmarket.dto.OwnProfile;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface UserAccountMapper {
    @Select("""
            SELECT id, password_hash, role_code, registration_status, account_status
            FROM users
            WHERE login_name = #{loginName}
            """)
    LoginAccount findByLoginName(@Param("loginName") String loginName);

    @Select("""
            SELECT login_name, real_name, phone, email, city, gender_code, registration_status, account_status
            FROM users
            WHERE id = #{userId} AND registration_status = 'APPROVED' AND account_status = 'ENABLED'
            """)
    OwnProfile findOwnProfile(@Param("userId") long userId);

    record LoginAccount(
            Long id, String passwordHash, String roleCode, String registrationStatus, String accountStatus) {
        @Override
        public String toString() {
            return "LoginAccount[id=" + id + "]";
        }
    }
}
