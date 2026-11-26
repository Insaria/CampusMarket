package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.dto.LoginAccountQuery;
import edu.bistu.campusmarket.dto.RegistrationRequest;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.UserRegistrationMapper;
import edu.bistu.campusmarket.mapper.UserRegistrationMapper.NewUser;
import edu.bistu.campusmarket.security.BankAccountEncryption;
import jakarta.servlet.http.HttpSession;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class UserRegistrationService {
    private final RegistrationCaptcha captcha;
    private final UserRegistrationMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final BankAccountEncryption encryption;

    public UserRegistrationService(RegistrationCaptcha captcha, UserRegistrationMapper mapper,
            PasswordEncoder passwordEncoder, BankAccountEncryption encryption) {
        this.captcha = captcha;
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.encryption = encryption;
    }

    @Transactional
    public void register(RegistrationRequest request, HttpSession session) {
        captcha.consume(session, request.captcha());
        String loginName = new LoginAccountQuery(request.loginName()).canonicalLoginName();
        if (mapper.loginNameExists(loginName)) {
            throw duplicateLoginName();
        }
        String email = request.email();
        int domain = email.lastIndexOf('@') + 1;
        email = email.substring(0, domain) + email.substring(domain).toLowerCase(Locale.ROOT);
        byte[] encryptedBankAccount = encryption.encrypt(request.bankAccount());
        NewUser user = new NewUser(loginName, passwordEncoder.encode(request.password()), request.realName(),
                request.phone(), email, request.city(), request.genderCode(), encryptedBankAccount);
        try {
            if (mapper.insert(user) != 1) {
                throw new IllegalStateException("Registration insert did not create one account");
            }
        } catch (DuplicateKeyException exception) {
            throw duplicateLoginName();
        }
    }

    private BusinessException duplicateLoginName() {
        return new BusinessException(HttpStatus.CONFLICT, "LOGIN_NAME_TAKEN", "登录名已被使用");
    }
}
