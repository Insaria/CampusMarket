package edu.bistu.campusmarket.controller;

import edu.bistu.campusmarket.common.ApiResponse;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.dto.SellerProductDtos.Draft;
import edu.bistu.campusmarket.dto.SellerProductDtos.Item;
import edu.bistu.campusmarket.dto.SellerProductDtos.ImageReceipt;
import edu.bistu.campusmarket.dto.SellerProductDtos.Page;
import edu.bistu.campusmarket.dto.SellerProductDtos.Stock;
import edu.bistu.campusmarket.security.ApiAccess;
import edu.bistu.campusmarket.security.CurrentUserService;
import edu.bistu.campusmarket.service.SellerProductService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Profile("db")
@ApiAccess(ApiAccess.Requirement.MERCHANT)
@RequestMapping("/api/sellers/me/products")
public class SellerProductController {
    private final SellerProductService service;

    public SellerProductController(SellerProductService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<Page> list(@RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.list(user.userId(), page, size));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Item>> create(
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @Valid @RequestBody Draft input) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.create(user.userId(), input)));
    }

    @PatchMapping(path = "/{id}/stock", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Item> adjust(@RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable @Min(1) long id, @Valid @RequestBody Stock input) {
        return ApiResponse.success(service.adjust(user.userId(), id, input));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Item> edit(@RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable @Min(1) long id, @Valid @RequestBody Draft input) {
        return ApiResponse.success(service.edit(user.userId(), id, input));
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<Item> submit(@RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable @Min(1) long id) {
        return ApiResponse.success(service.submit(user.userId(), id));
    }

    @PostMapping(path = "/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ImageReceipt>> appendImage(
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable @Min(1) long id, @RequestPart("image") MultipartFile image) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.appendImage(user.userId(), id, image)));
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<Item> publish(@RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable @Min(1) long id) {
        return ApiResponse.success(service.publish(user.userId(), id));
    }

    @PostMapping("/{id}/unpublish")
    public ApiResponse<Item> unpublish(@RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable @Min(1) long id) {
        return ApiResponse.success(service.unpublish(user.userId(), id));
    }
}
