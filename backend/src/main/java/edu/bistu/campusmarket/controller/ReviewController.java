package edu.bistu.campusmarket.controller;

import edu.bistu.campusmarket.common.ApiResponse;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.dto.ReviewDtos.Created;
import edu.bistu.campusmarket.dto.ReviewDtos.ProductReviewPage;
import edu.bistu.campusmarket.dto.ReviewDtos.RatingSummary;
import edu.bistu.campusmarket.dto.ReviewDtos.ReputationHistory;
import edu.bistu.campusmarket.dto.ReviewDtos.TradeReview;
import edu.bistu.campusmarket.dto.ReviewDtos.Write;
import edu.bistu.campusmarket.security.ApiAccess;
import edu.bistu.campusmarket.security.CurrentUserService;
import edu.bistu.campusmarket.service.ReviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("db")
@ApiAccess(ApiAccess.Requirement.USER)
public class ReviewController {
    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    @PostMapping("/api/order-items/{orderItemId}/review")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Created> product(@RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable @Min(1) long orderItemId, @Valid @RequestBody Write input) {
        return ApiResponse.success(service.reviewProduct(user.userId(), orderItemId, input));
    }

    @PostMapping("/api/orders/{orderId}/seller-review")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Created> seller(@RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable @Min(1) long orderId, @Valid @RequestBody Write input) {
        return ApiResponse.success(service.reviewSeller(user.userId(), orderId, input));
    }

    @PostMapping("/api/sellers/me/orders/{orderId}/buyer-review")
    @ApiAccess(ApiAccess.Requirement.SELLER_HISTORY)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Created> purchase(@RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable @Min(1) long orderId, @Valid @RequestBody Write input) {
        return ApiResponse.success(service.reviewBuyerPurchase(user.userId(), orderId, input));
    }

    @PostMapping("/api/sellers/me/return-requests/{requestId}/buyer-review")
    @ApiAccess(ApiAccess.Requirement.SELLER_HISTORY)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Created> returned(@RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable @Min(1) long requestId, @Valid @RequestBody Write input) {
        return ApiResponse.success(service.reviewBuyerReturn(user.userId(), requestId, input));
    }

    @GetMapping("/api/products/{productId}/reviews")
    @ApiAccess(ApiAccess.Requirement.PUBLIC)
    public ApiResponse<ProductReviewPage> productReviews(@PathVariable @Min(1) long productId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.productReviews(productId, page, size));
    }

    @GetMapping("/api/orders/{orderId}/trade-reviews")
    public ApiResponse<List<TradeReview>> tradeReviews(
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user, @PathVariable @Min(1) long orderId) {
        return ApiResponse.success(service.tradeReviews(user.userId(), orderId));
    }

    @GetMapping("/api/sellers/me/satisfaction")
    @ApiAccess(ApiAccess.Requirement.SELLER_HISTORY)
    public ApiResponse<RatingSummary> satisfaction(@RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user) {
        return ApiResponse.success(service.sellerSatisfaction(user.userId()));
    }

    @GetMapping("/api/users/me/reputation")
    public ApiResponse<RatingSummary> reputation(@RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user) {
        return ApiResponse.success(service.buyerReputation(user.userId()));
    }

    @GetMapping("/api/users/me/trade-reviews")
    public ResponseEntity<ApiResponse<ReputationHistory>> history(
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.reputationHistory(user.userId(), page, size)));
    }
}
