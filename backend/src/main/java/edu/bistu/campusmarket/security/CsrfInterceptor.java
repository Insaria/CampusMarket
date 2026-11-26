package edu.bistu.campusmarket.security;

import edu.bistu.campusmarket.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

public class CsrfInterceptor implements HandlerInterceptor {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (SAFE_METHODS.contains(request.getMethod())) {
            return true;
        }
        var headers = request.getHeaders(CsrfTokens.HEADER_NAME);
        String supplied = headers.hasMoreElements() ? headers.nextElement() : null;
        if ((request.getRequestedSessionId() != null && !request.isRequestedSessionIdValid())
                || headers.hasMoreElements() || !CsrfTokens.matches(request.getSession(false), supplied)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "INVALID_CSRF_TOKEN", "请求校验失败，请重新获取 CSRF Token");
        }
        return true;
    }
}
