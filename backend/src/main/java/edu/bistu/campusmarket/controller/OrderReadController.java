package edu.bistu.campusmarket.controller;

import edu.bistu.campusmarket.common.ApiResponse;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.dto.OrderReadDtos.*;
import edu.bistu.campusmarket.security.ApiAccess;
import edu.bistu.campusmarket.security.CurrentUserService;
import edu.bistu.campusmarket.service.OrderReadService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("db")
public class OrderReadController {
    private final OrderReadService service;

    public OrderReadController(OrderReadService service) {
        this.service = service;
    }

    @GetMapping("/api/orders")
    @ApiAccess(ApiAccess.Requirement.USER)
    public ResponseEntity<ApiResponse<Page>> buyerOrders(
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser buyer,
            @RequestParam(required = false) Filter status, @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return response(service.list(buyer.userId(), false, status, page, size));
    }

    @GetMapping("/api/orders/{orderId}")
    @ApiAccess(ApiAccess.Requirement.USER)
    public ResponseEntity<ApiResponse<Details>> buyerDetail(
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser buyer,
            @PathVariable @Min(1) long orderId) {
        return response(service.detail(buyer.userId(), false, orderId));
    }

    @GetMapping("/api/sellers/me/orders")
    @ApiAccess(ApiAccess.Requirement.SELLER_HISTORY)
    public ResponseEntity<ApiResponse<Page>> sellerOrders(
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser seller,
            @RequestParam(required = false) Filter status, @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return response(service.list(seller.userId(), true, status, page, size));
    }

    @GetMapping("/api/sellers/me/orders/{orderId}")
    @ApiAccess(ApiAccess.Requirement.SELLER_HISTORY)
    public ResponseEntity<ApiResponse<Details>> sellerDetail(
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser seller,
            @PathVariable @Min(1) long orderId) {
        return response(service.detail(seller.userId(), true, orderId));
    }

    private static <T> ResponseEntity<ApiResponse<T>> response(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(body));
    }
}
