package edu.bistu.campusmarket.controller;

import edu.bistu.campusmarket.common.ApiResponse;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.dto.CatalogDtos.Page;
import edu.bistu.campusmarket.security.ApiAccess;
import edu.bistu.campusmarket.security.CurrentUserService;
import edu.bistu.campusmarket.service.FavoriteService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("db")
@ApiAccess(ApiAccess.Requirement.USER)
@RequestMapping("/api/favorites")
public class FavoriteController {
    private final FavoriteService service;

    public FavoriteController(FavoriteService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<Page> list(@RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.list(user.userId(), page, size));
    }

    @PutMapping("/{productId}")
    public ApiResponse<Void> add(@RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable @Min(1) long productId) {
        service.add(user.userId(), productId);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{productId}")
    public ApiResponse<Void> remove(@RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable @Min(1) long productId) {
        service.remove(user.userId(), productId);
        return ApiResponse.success(null);
    }
}
