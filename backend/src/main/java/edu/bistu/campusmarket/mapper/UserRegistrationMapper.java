package edu.bistu.campusmarket.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface UserRegistrationMapper {
    @Select("SELECT EXISTS (SELECT 1 FROM users WHERE login_name = #{loginName})")
    boolean loginNameExists(@Param("loginName") String loginName);

    @Insert("""
            INSERT INTO users (login_name, password_hash, real_name, phone, email, city,
                               gender_code, bank_account_enc, role_code, registration_status, account_status)
            VALUES (#{loginName}, #{passwordHash}, #{realName}, #{phone}, #{email}, #{city},
                    #{genderCode}, #{bankAccountEncrypted}, 'USER', 'PENDING', 'ENABLED')
            """)
    int insert(NewUser user);

    record NewUser(String loginName, String passwordHash, String realName, String phone, String email,
            String city, String genderCode, byte[] bankAccountEncrypted) {
        @Override
        public String toString() {
            return "NewUser[redacted]";
        }
    }
}
