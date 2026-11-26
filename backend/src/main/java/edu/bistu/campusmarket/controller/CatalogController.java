package edu.bistu.campusmarket.controller;

import edu.bistu.campusmarket.common.ApiResponse;
import edu.bistu.campusmarket.dto.CatalogDtos.Category;
import edu.bistu.campusmarket.dto.CatalogDtos.Page;
import edu.bistu.campusmarket.dto.CatalogDtos.Product;
import edu.bistu.campusmarket.dto.CatalogDtos.Sort;
import edu.bistu.campusmarket.dto.CatalogDtos.Shop;
import edu.bistu.campusmarket.dto.CatalogDtos.SoldPage;
import edu.bistu.campusmarket.security.ApiAccess;
import edu.bistu.campusmarket.service.CatalogService;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("db")
@ApiAccess(ApiAccess.Requirement.PUBLIC)
public class CatalogController {
    private final CatalogService service;

    public CatalogController(CatalogService service) {
        this.service = service;
    }

    @GetMapping("/api/categories")
    public ApiResponse<List<Category>> categories() {
        return ApiResponse.success(service.categories());
    }

    @GetMapping("/api/products")
    public ApiResponse<Page> list(@RequestParam(required = false) @Size(max = 120) String keyword,
            @RequestParam(required = false) @Min(1) Long categoryId,
            @RequestParam(required = false) @Min(1) Long sellerId,
            @RequestParam(required = false) @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal minPrice,
            @RequestParam(required = false) @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal maxPrice,
            @RequestParam(defaultValue = "NEWEST") Sort sort,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.list(keyword, categoryId, sellerId, minPrice, maxPrice, sort, page, size));
    }

    @GetMapping("/api/products/{id}")
    public ApiResponse<Product> find(@PathVariable @Min(1) long id) {
        return ApiResponse.success(service.find(id));
    }

    @GetMapping("/api/shops/{id}")
    public ResponseEntity<ApiResponse<Shop>> shop(@PathVariable @Min(1) long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.shop(id)));
    }

    @GetMapping("/api/products/sold")
    public ApiResponse<SoldPage> sold(@RequestParam(required = false) @Min(1) Long sellerId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.sold(sellerId, page, size));
    }

    @GetMapping("/api/products/{id}/images")
    public ApiResponse<List<Integer>> images(@PathVariable @Min(1) long id) {
        return ApiResponse.success(service.imagePositions(id));
    }

    @GetMapping("/api/products/{id}/images/{position}")
    public ResponseEntity<byte[]> image(@PathVariable @Min(1) long id, @PathVariable @Min(0) int position) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(service.image(id, position));
    }
}
