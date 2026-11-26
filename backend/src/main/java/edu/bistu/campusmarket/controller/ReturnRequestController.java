package edu.bistu.campusmarket.controller;

import edu.bistu.campusmarket.common.ApiResponse;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.dto.ReturnRequestDtos.Application;
import edu.bistu.campusmarket.dto.ReturnRequestDtos.Item;
import edu.bistu.campusmarket.dto.ReturnRequestDtos.Page;
import edu.bistu.campusmarket.dto.ReturnRequestDtos.ReceiptConfirmation;
import edu.bistu.campusmarket.dto.ReturnRequestDtos.Received;
import edu.bistu.campusmarket.dto.ReturnRequestDtos.Review;
import edu.bistu.campusmarket.dto.ReturnRequestDtos.Status;
import edu.bistu.campusmarket.security.ApiAccess;
import edu.bistu.campusmarket.security.CurrentUserService;
import edu.bistu.campusmarket.service.ReturnReceiptService;
import edu.bistu.campusmarket.service.ReturnRequestService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("db")
public class ReturnRequestController {
    private final ReturnRequestService service;
    private final ReturnReceiptService receipts;

    public ReturnRequestController(ReturnRequestService service, ReturnReceiptService receipts) {
        this.service = service;
        this.receipts = receipts;
    }

    @PostMapping(path = "/api/orders/{orderId}/return-requests", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ApiAccess(ApiAccess.Requirement.USER)
    public ApiResponse<Item> apply(@PathVariable @Min(1) long orderId, @Valid @RequestBody Application input,
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser buyer) {
        return ApiResponse.success(service.apply(buyer.userId(), orderId, input));
    }

    @GetMapping("/api/sellers/me/return-requests")
    @ApiAccess(ApiAccess.Requirement.SELLER_HISTORY)
    public ApiResponse<Page> list(@RequestParam(required = false) Status status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser seller) {
        return ApiResponse.success(service.list(seller.userId(), status, page, size));
    }

    @GetMapping("/api/sellers/me/return-requests/{requestId}")
    @ApiAccess(ApiAccess.Requirement.SELLER_HISTORY)
    public ApiResponse<Item> find(@PathVariable @Min(1) long requestId,
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser seller) {
        return ApiResponse.success(service.find(seller.userId(), requestId));
    }

    @PatchMapping(path = "/api/sellers/me/return-requests/{requestId}/decision", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ApiAccess(ApiAccess.Requirement.SELLER_HISTORY)
    public ApiResponse<Item> review(@PathVariable @Min(1) long requestId, @Valid @RequestBody Review input,
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser seller) {
        return ApiResponse.success(service.review(seller.userId(), requestId, input));
    }

    @PostMapping(path = "/api/sellers/me/return-requests/{requestId}/receive", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ApiAccess(ApiAccess.Requirement.SELLER_HISTORY)
    public ApiResponse<Received> receive(@PathVariable @Min(1) long requestId, @Valid @RequestBody ReceiptConfirmation input,
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser seller) {
        return ApiResponse.success(receipts.receive(seller.userId(), requestId));
    }
}
