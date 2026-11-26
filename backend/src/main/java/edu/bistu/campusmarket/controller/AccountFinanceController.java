package edu.bistu.campusmarket.controller;

import edu.bistu.campusmarket.common.ApiResponse;
import edu.bistu.campusmarket.dto.AccountFinanceDtos.PointsView;
import edu.bistu.campusmarket.dto.AccountFinanceDtos.WalletView;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.security.ApiAccess;
import edu.bistu.campusmarket.security.CurrentUserService;
import edu.bistu.campusmarket.service.AccountFinanceService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("db")
@ApiAccess(ApiAccess.Requirement.USER)
public class AccountFinanceController {
    private final AccountFinanceService service;

    public AccountFinanceController(AccountFinanceService service) {
        this.service = service;
    }

    @GetMapping("/api/wallet")
    public ResponseEntity<ApiResponse<WalletView>> wallet(
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.wallet(user.userId(), page, size)));
    }

    @GetMapping("/api/points")
    public ResponseEntity<ApiResponse<PointsView>> points(
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.points(user.userId(), page, size)));
    }
}
