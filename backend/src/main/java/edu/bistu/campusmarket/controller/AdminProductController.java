package edu.bistu.campusmarket.controller;

import edu.bistu.campusmarket.common.ApiResponse;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.dto.SellerProductDtos.Item;
import edu.bistu.campusmarket.dto.SellerProductDtos.Review;
import edu.bistu.campusmarket.dto.SellerProductDtos.ReviewDetail;
import edu.bistu.campusmarket.security.ApiAccess;
import edu.bistu.campusmarket.security.CurrentUserService;
import edu.bistu.campusmarket.service.SellerProductService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("db")
@ApiAccess(ApiAccess.Requirement.ADMIN)
@RequestMapping("/api/admin/products")
public class AdminProductController {
    private final SellerProductService service;

    public AdminProductController(SellerProductService service) {
        this.service = service;
    }

    @GetMapping("/pending")
    public ApiResponse<List<Item>> pending(@RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.pending(page, size));
    }

    @GetMapping("/pending/count")
    public ResponseEntity<ApiResponse<Long>> pendingCount() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.pendingCount()));
    }

    @PatchMapping("/{id}/review")
    public ApiResponse<Item> review(@PathVariable @Min(1) long id, @Valid @RequestBody Review input,
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser actor) {
        return ApiResponse.success(service.review(actor.userId(), id, input));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ReviewDetail>> detail(@PathVariable @Min(1) long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.reviewDetail(id)));
    }

    @GetMapping("/{id}/images/{position}")
    public ResponseEntity<byte[]> image(@PathVariable @Min(1) long id, @PathVariable @Min(0) int position) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(service.reviewImage(id, position));
    }
}
