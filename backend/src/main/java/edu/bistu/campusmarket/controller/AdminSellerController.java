package edu.bistu.campusmarket.controller;

import edu.bistu.campusmarket.common.ApiResponse;
import edu.bistu.campusmarket.dto.AdminSellerDtos.*;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.security.ApiAccess;
import edu.bistu.campusmarket.security.CurrentUserService;
import edu.bistu.campusmarket.service.AdminSellerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("db")
@ApiAccess(ApiAccess.Requirement.ADMIN)
@RequestMapping("/api/admin/sellers")
public class AdminSellerController {
    private final AdminSellerService service;

    public AdminSellerController(AdminSellerService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<Page> list(@RequestParam(required = false) ReviewStatus reviewStatus,
            @RequestParam(defaultValue = "false") boolean includePersonal,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.list(reviewStatus, includePersonal, page, size));
    }

    @GetMapping("/levels")
    public ApiResponse<List<Level>> levels() {
        return ApiResponse.success(service.levels());
    }

    @GetMapping("/{id}")
    public ApiResponse<Seller> find(@PathVariable @Min(1) long id) {
        return ApiResponse.success(service.findShop(id));
    }

    @GetMapping("/{id}/documents/{kind}")
    public ResponseEntity<byte[]> document(@PathVariable @Min(1) long id, @PathVariable DocumentKind kind,
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser actor) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(service.document(actor.userId(), id, kind));
    }

    @PatchMapping(path = "/{id}/review", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Seller> review(@PathVariable @Min(1) long id, @Valid @RequestBody Review input,
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser actor) {
        return ApiResponse.success(service.review(actor.userId(), id, input));
    }

    @PatchMapping(path = "/{id}/level", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Seller> level(@PathVariable @Min(1) long id, @Valid @RequestBody LevelChange input,
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser actor) {
        return ApiResponse.success(service.changeLevel(actor.userId(), id, input));
    }

    @PatchMapping(path = "/{id}/operation", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Seller> operation(@PathVariable @Min(1) long id, @Valid @RequestBody OperationChange input,
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser actor) {
        return ApiResponse.success(service.changeOperation(actor.userId(), id, input));
    }
}
