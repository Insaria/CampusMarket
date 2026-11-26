package edu.bistu.campusmarket.controller;

import edu.bistu.campusmarket.common.ApiResponse;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.dto.OrderFulfillmentResult;
import edu.bistu.campusmarket.security.ApiAccess;
import edu.bistu.campusmarket.security.CurrentUserService;
import edu.bistu.campusmarket.service.OrderFulfillmentService;
import jakarta.validation.constraints.Min;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("db")
public class OrderFulfillmentController {
    private final OrderFulfillmentService service;

    public OrderFulfillmentController(OrderFulfillmentService service) {
        this.service = service;
    }

    @PostMapping("/api/sellers/me/orders/{orderId}/deliver")
    @ApiAccess(ApiAccess.Requirement.SELLER_HISTORY)
    public ApiResponse<OrderFulfillmentResult> deliver(
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser seller,
            @PathVariable @Min(1) long orderId) {
        return ApiResponse.success(service.deliver(seller.userId(), orderId));
    }

    @PostMapping("/api/orders/{orderId}/confirm-receipt")
    @ApiAccess(ApiAccess.Requirement.USER)
    public ApiResponse<OrderFulfillmentResult> confirmReceipt(
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser buyer,
            @PathVariable @Min(1) long orderId) {
        return ApiResponse.success(service.confirmReceipt(buyer.userId(), orderId));
    }
}
