package edu.bistu.campusmarket.controller;

import edu.bistu.campusmarket.common.ApiResponse;
import edu.bistu.campusmarket.dto.AdminUserDtos.AccountChange;
import edu.bistu.campusmarket.dto.AdminUserDtos.AccountStatus;
import edu.bistu.campusmarket.dto.AdminUserDtos.AccountStatusRequest;
import edu.bistu.campusmarket.dto.AdminUserDtos.ProfileUpdate;
import edu.bistu.campusmarket.dto.AdminUserDtos.RegistrationStatus;
import edu.bistu.campusmarket.dto.AdminUserDtos.ReviewDecision;
import edu.bistu.campusmarket.dto.AdminUserDtos.ReviewRequest;
import edu.bistu.campusmarket.dto.AdminUserDtos.UserPage;
import edu.bistu.campusmarket.dto.AdminUserDtos.UserView;
import edu.bistu.campusmarket.dto.AdminUserDtos.Recharge;
import edu.bistu.campusmarket.dto.AdminUserDtos.RechargeReceipt;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.security.ApiAccess;
import edu.bistu.campusmarket.security.CurrentUserService;
import edu.bistu.campusmarket.service.AdminUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("db")
@ApiAccess(ApiAccess.Requirement.ADMIN)
@RequestMapping("/api/admin/users")
public class AdminUserController {
    private final AdminUserService service;

    public AdminUserController(AdminUserService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<UserPage> list(@RequestParam(required = false) @Size(max = 64) String loginName,
            @RequestParam(required = false) RegistrationStatus registrationStatus,
            @RequestParam(required = false) AccountStatus accountStatus,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.list(loginName, registrationStatus, accountStatus, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<UserView> find(@PathVariable @Min(1) long id) {
        return ApiResponse.success(service.find(id));
    }

    @GetMapping("/pending/count")
    public ResponseEntity<ApiResponse<Long>> pendingCount() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.pendingCount()));
    }

    @PatchMapping(path = "/{id}/registration", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<UserView> review(@PathVariable @Min(1) long id, @Valid @RequestBody ReviewRequest input,
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser actor) {
        return ApiResponse.success(service.review(actor.userId(), id, ReviewDecision.valueOf(input.decision())));
    }

    @PatchMapping(path = "/{id}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<UserView> changeStatus(@PathVariable @Min(1) long id,
            @Valid @RequestBody AccountStatusRequest input,
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser actor) {
        return ApiResponse.success(service.changeStatus(actor.userId(), id, AccountChange.valueOf(input.status())));
    }

    @PostMapping(path = "/{id}/wallet/recharges", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<RechargeReceipt> recharge(@PathVariable @Min(1) long id, @Valid @RequestBody Recharge input,
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser actor) {
        return ApiResponse.success(service.recharge(actor.userId(), id, input));
    }

    @PatchMapping(path = "/{id}/profile", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<UserView> updateProfile(@PathVariable @Min(1) long id, @Valid @RequestBody ProfileUpdate input,
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser actor) {
        return ApiResponse.success(service.updateProfile(actor.userId(), id, input));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<UserView> delete(@PathVariable @Min(1) long id,
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser actor) {
        return ApiResponse.success(service.delete(actor.userId(), id));
    }
}
