package edu.bistu.campusmarket.controller;

import edu.bistu.campusmarket.common.ApiResponse;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.dto.LoginRequest;
import edu.bistu.campusmarket.security.ApiAccess;
import edu.bistu.campusmarket.security.CurrentUserService;
import edu.bistu.campusmarket.security.CsrfTokens;
import edu.bistu.campusmarket.security.LoginAttemptLimiter;
import edu.bistu.campusmarket.service.LoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("db")
@RequestMapping("/api/auth")
public class LoginController {
    private final LoginService loginService;
    private final LoginAttemptLimiter attemptLimiter;

    public LoginController(LoginService loginService, LoginAttemptLimiter attemptLimiter) {
        this.loginService = loginService;
        this.attemptLimiter = attemptLimiter;
    }

    @ApiAccess(ApiAccess.Requirement.PUBLIC)
    @GetMapping("/csrf")
    public ResponseEntity<ApiResponse<Map<String, String>>> csrf(HttpServletRequest request) {
        String token = CsrfTokens.getOrCreate(request.getSession(true));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(Map.of("token", token)));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<AuthenticatedUser>> currentUser(
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(user));
    }

    @ApiAccess(ApiAccess.Requirement.PUBLIC)
    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<AuthenticatedUser>> login(
            @Valid @RequestBody LoginRequest input, HttpServletRequest request) {
        AuthenticatedUser user = attemptLimiter.authenticate(input.accountQuery().canonicalLoginName(),
                request.getRemoteAddr(), () -> loginService.authenticate(input));
        HttpSession existingSession = request.getSession(false);
        if (existingSession != null) {
            existingSession.invalidate();
        }
        HttpSession session = request.getSession(true);
        CsrfTokens.getOrCreate(session);
        session.setAttribute(AuthenticatedUser.SESSION_ATTRIBUTE, user);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(user));
    }

    @ApiAccess(ApiAccess.Requirement.PUBLIC)
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request, HttpServletResponse response) {
        invalidateSession(request);
        SessionCookieConfig config = request.getServletContext().getSessionCookieConfig();
        Cookie cookie = new Cookie(config.getName() == null ? "JSESSIONID" : config.getName(), "");
        cookie.setMaxAge(0);
        String path = config.getPath();
        if (path == null) {
            path = request.getContextPath().isEmpty() ? "/" : request.getContextPath();
        }
        cookie.setPath(path);
        if (config.getDomain() != null) {
            cookie.setDomain(config.getDomain());
        }
        cookie.setHttpOnly(true);
        cookie.setSecure(config.isSecure() || request.isSecure());
        response.addCookie(cookie);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(null));
    }

    private void invalidateSession(HttpServletRequest request) {
        try {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
        } catch (IllegalStateException ignored) {
            return;
        }
    }
}
