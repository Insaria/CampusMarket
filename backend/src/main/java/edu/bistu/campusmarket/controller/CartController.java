package edu.bistu.campusmarket.controller;

import edu.bistu.campusmarket.common.ApiResponse;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.mapper.CartMapper.Row;
import edu.bistu.campusmarket.security.ApiAccess;
import edu.bistu.campusmarket.security.CurrentUserService;
import edu.bistu.campusmarket.service.CartService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("db")
@ApiAccess(ApiAccess.Requirement.USER)
@RequestMapping("/api/cart")
public class CartController {
    private final CartService service;

    public CartController(CartService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Row>> list(@RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user) {
        return ApiResponse.success(service.list(user.userId()));
    }

    @PutMapping(path = "/items/{productId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Void> put(@RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable @Min(1) long productId, @Valid @RequestBody Quantity input) {
        service.put(user.userId(), productId, input.quantity());
        return ApiResponse.success(null);
    }

    @DeleteMapping("/items/{productId}")
    public ApiResponse<Void> remove(@RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable @Min(1) long productId) {
        service.remove(user.userId(), productId);
        return ApiResponse.success(null);
    }

    public record Quantity(@NotNull @Min(1) Integer quantity) {
    }
}
