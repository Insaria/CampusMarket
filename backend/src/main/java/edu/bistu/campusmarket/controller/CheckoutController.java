package edu.bistu.campusmarket.controller;

import edu.bistu.campusmarket.common.ApiResponse;
import edu.bistu.campusmarket.dto.CheckoutDtos.BatchDetails;
import edu.bistu.campusmarket.dto.CheckoutDtos.PendingPage;
import edu.bistu.campusmarket.dto.CheckoutDtos.PaidRequest;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.dto.CheckoutDtos.Request;
import edu.bistu.campusmarket.dto.CheckoutDtos.Result;
import edu.bistu.campusmarket.dto.OrderCancellationResult;
import edu.bistu.campusmarket.dto.WalletPaymentRequest;
import edu.bistu.campusmarket.dto.WalletPaymentResult;
import edu.bistu.campusmarket.security.ApiAccess;
import edu.bistu.campusmarket.security.CurrentUserService;
import edu.bistu.campusmarket.service.CheckoutService;
import edu.bistu.campusmarket.service.OrderCancellationService;
import edu.bistu.campusmarket.service.WalletPaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("db")
@ApiAccess(ApiAccess.Requirement.USER)
public class CheckoutController {
    private final CheckoutService service;
    private final WalletPaymentService paymentService;
    private final OrderCancellationService cancellationService;

    public CheckoutController(CheckoutService service, WalletPaymentService paymentService,
            OrderCancellationService cancellationService) {
        this.service = service;
        this.paymentService = paymentService;
        this.cancellationService = cancellationService;
    }

    @GetMapping("/api/checkout-batches")
    public ResponseEntity<ApiResponse<PendingPage>> pending(
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser buyer,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.pending(buyer.userId(), page, size)));
    }

    @GetMapping("/api/checkout-batches/{batchId}")
    public ResponseEntity<ApiResponse<BatchDetails>> batch(
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser buyer, @PathVariable @Min(1) long batchId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.batch(buyer.userId(), batchId)));
    }

    @PostMapping(path = "/api/cart/checkout", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Result> checkout(@RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser buyer,
            @Valid @RequestBody Request input) {
        return ApiResponse.success(service.checkout(buyer.userId(), input));
    }

    @PostMapping(path = "/api/cart/checkout-and-pay", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<BatchDetails> checkoutAndPay(@RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser buyer,
            @Valid @RequestBody PaidRequest input) {
        return ApiResponse.success(service.checkoutAndPay(buyer.userId(), input));
    }

    @PostMapping("/api/checkout-batches/{batchId}/pay")
    public ApiResponse<WalletPaymentResult> pay(
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser buyer,
            @PathVariable @Min(1) long batchId, @Valid @RequestBody(required = false) WalletPaymentRequest input) {
        return ApiResponse.success(paymentService.pay(buyer.userId(), batchId, input));
    }

    @PostMapping("/api/checkout-batches/{batchId}/cancel")
    public ApiResponse<OrderCancellationResult> cancel(
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser buyer,
            @PathVariable @Min(1) long batchId) {
        return ApiResponse.success(cancellationService.cancel(buyer.userId(), batchId));
    }
}
