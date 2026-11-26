package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.dto.LoginRequest;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.UserAccountMapper.LoginAccount;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@Profile("db")
public class LoginService {
    private final LoginAccountService accountService;
    private final PasswordEncoder passwordEncoder;
    private final String dummyHash;

    public LoginService(LoginAccountService accountService, PasswordEncoder passwordEncoder) {
        this.accountService = accountService;
        this.passwordEncoder = passwordEncoder;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public AuthenticatedUser authenticate(LoginRequest request) {
        LoginAccount account = accountService.findForAuthentication(request.accountQuery()).orElse(null);
        boolean matches;
        try {
            matches = passwordEncoder.matches(request.password(), account == null ? dummyHash : account.passwordHash());
        } catch (IllegalArgumentException exception) {
            matches = false;
        }
        if (account == null || !matches) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "账号或密码错误");
        }
        if (!"ENABLED".equals(account.accountStatus())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCOUNT_UNAVAILABLE", "账号不可用");
        }
        if (!"APPROVED".equals(account.registrationStatus())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "REGISTRATION_NOT_APPROVED", "账号尚未通过审核");
        }
        return new AuthenticatedUser(account.id(), account.roleCode());
    }
}
