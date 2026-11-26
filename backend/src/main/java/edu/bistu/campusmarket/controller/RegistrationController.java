package edu.bistu.campusmarket.controller;

import edu.bistu.campusmarket.common.ApiResponse;
import edu.bistu.campusmarket.dto.RegistrationRequest;
import edu.bistu.campusmarket.security.ApiAccess;
import edu.bistu.campusmarket.service.RegistrationCaptcha;
import edu.bistu.campusmarket.service.UserRegistrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("db")
@ApiAccess(ApiAccess.Requirement.PUBLIC)
@RequestMapping("/api/auth")
public class RegistrationController {
    private final RegistrationCaptcha captcha;
    private final UserRegistrationService registrationService;

    public RegistrationController(RegistrationCaptcha captcha, UserRegistrationService registrationService) {
        this.captcha = captcha;
        this.registrationService = registrationService;
    }

    @GetMapping(value = "/captcha", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> captcha(HttpServletRequest request) throws IOException {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore())
                .body(captcha.issue(request.getSession(true)));
    }

    @PostMapping(path = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Map<String, String>>> register(
            @Valid @RequestBody RegistrationRequest input, HttpServletRequest request) {
        registrationService.register(input, request.getSession(false));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(Map.of("registrationStatus", "PENDING")));
    }
}
