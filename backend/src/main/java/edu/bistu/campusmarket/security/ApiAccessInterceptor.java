package edu.bistu.campusmarket.security;

import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.AccountAccessMapper.AccountAccess;
import edu.bistu.campusmarket.security.ApiAccess.Requirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

public class ApiAccessInterceptor implements HandlerInterceptor {
    private final CurrentUserService currentUserService;

    public ApiAccessInterceptor(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        ApiAccess access = method.getMethodAnnotation(ApiAccess.class);
        if (access == null) {
            access = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), ApiAccess.class);
        }
        if (access == null && !AnnotatedElementUtils.hasAnnotation(method.getBeanType(), RestController.class)) {
            return true;
        }
        Requirement requirement = access == null ? Requirement.AUTHENTICATED : access.value();
        if (requirement == Requirement.PUBLIC) {
            return true;
        }

        AccountAccess account = currentUserService.requireAccount(request);
        boolean allowed = switch (requirement) {
            case AUTHENTICATED -> "USER".equals(account.roleCode()) || "ADMIN".equals(account.roleCode());
            case USER -> "USER".equals(account.roleCode());
            case ADMIN -> "ADMIN".equals(account.roleCode());
            case MERCHANT -> canTradeAsSeller(account, false);
            case SELLER_HISTORY -> canTradeAsSeller(account, true);
            case PUBLIC -> true;
        };
        if (!allowed) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "权限不足");
        }
        request.setAttribute(CurrentUserService.REQUEST_ATTRIBUTE,
                new AuthenticatedUser(account.id(), account.roleCode()));
        return true;
    }

    private boolean canTradeAsSeller(AccountAccess account, boolean history) {
        return "USER".equals(account.roleCode()) && account.merchantId() != null
                && "APPROVED".equals(account.reviewStatus())
                && (history && "SUSPENDED".equals(account.operationStatus())
                    || "ACTIVE".equals(account.operationStatus())
                        && ("PERSONAL".equals(account.sellerType())
                            || "BUSINESS".equals(account.sellerType()) && Integer.valueOf(1).equals(account.levelEnabled())));
    }
}
