package edu.bistu.campusmarket.security;

import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.AccountAccessMapper;
import edu.bistu.campusmarket.mapper.AccountAccessMapper.AccountAccess;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@Profile("db")
public class CurrentUserService {
    public static final String REQUEST_ATTRIBUTE = "currentUser";

    private final AccountAccessMapper mapper;

    public CurrentUserService(AccountAccessMapper mapper) {
        this.mapper = mapper;
    }

    public AccountAccess requireAccount(HttpServletRequest request) {
        AuthenticatedUser identity = readSession(request);
        AccountAccess account = mapper.findByUserId(identity.userId());
        if (account == null) {
            throw loginRequired();
        }
        if (!"APPROVED".equals(account.registrationStatus()) || !"ENABLED".equals(account.accountStatus())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCOUNT_UNAVAILABLE", "账号不可用");
        }
        return account;
    }

    private AuthenticatedUser readSession(HttpServletRequest request) {
        if (request.getRequestedSessionId() != null && !request.isRequestedSessionIdValid()) {
            throw loginRequired();
        }
        try {
            HttpSession session = request.getSession(false);
            if (session != null && session.getAttribute(AuthenticatedUser.SESSION_ATTRIBUTE)
                    instanceof AuthenticatedUser identity && identity.userId() != null && identity.userId() > 0) {
                return identity;
            }
        } catch (IllegalStateException exception) {
            throw loginRequired();
        }
        throw loginRequired();
    }

    private BusinessException loginRequired() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "请先登录");
    }
}
