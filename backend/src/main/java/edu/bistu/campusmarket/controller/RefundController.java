package edu.bistu.campusmarket.controller;

import edu.bistu.campusmarket.common.ApiResponse;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.dto.RefundResult;
import edu.bistu.campusmarket.security.ApiAccess;
import edu.bistu.campusmarket.security.CurrentUserService;
import edu.bistu.campusmarket.service.RefundService;
import jakarta.validation.constraints.Min;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("db")
@ApiAccess(ApiAccess.Requirement.SELLER_HISTORY)
public class RefundController {
    private final RefundService service;

    public RefundController(RefundService service) {
        this.service = service;
    }

    @PostMapping("/api/sellers/me/return-requests/{requestId}/refund")
    public ResponseEntity<ApiResponse<RefundResult>> refund(@PathVariable @Min(1) long requestId,
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser seller) {
        RefundResult result = service.refund(seller.userId(), requestId);
        if ("PENDING_CASH".equals(result.refundStatus())) {
            // Return 409 after the service transaction has committed the pending record.
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ApiResponse<>(RefundService.BALANCE_INSUFFICIENT, "卖家钱包余额不足，补足后可重试", result));
        }
        if ("PENDING_POINTS".equals(result.refundStatus())) {
            return ResponseEntity.accepted().body(new ApiResponse<>("OK", "已登记，待处理积分", result));
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
