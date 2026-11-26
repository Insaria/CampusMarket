package edu.bistu.campusmarket.controller;

import edu.bistu.campusmarket.common.ApiResponse;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.security.ApiAccess;
import edu.bistu.campusmarket.security.CurrentUserService;
import edu.bistu.campusmarket.service.ProductMessageService;
import edu.bistu.campusmarket.service.ProductMessageService.Sent;
import edu.bistu.campusmarket.service.ProductMessageService.ThreadPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("db")
@ApiAccess(ApiAccess.Requirement.USER)
@RequestMapping("/api")
public class ProductMessageController {
    private final ProductMessageService service;

    public ProductMessageController(ProductMessageService service) {
        this.service = service;
    }

    @PostMapping("/products/{id}/messages")
    public ApiResponse<Sent> start(@RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable @Min(1) long id, @Valid @RequestBody Text input) {
        return ApiResponse.success(service.start(user.userId(), id, input.content()));
    }

    @GetMapping("/product-messages/{id}")
    public ResponseEntity<ApiResponse<ThreadPage>> thread(
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable @Min(1) long id, @RequestParam(required = false) @Min(1) Long before) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.thread(user.userId(), id, before)));
    }

    @PostMapping("/product-messages/{id}/replies")
    public ApiResponse<Sent> reply(@RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable @Min(1) long id, @Valid @RequestBody Text input) {
        return ApiResponse.success(service.reply(user.userId(), id, input.content()));
    }

    public record Text(@NotBlank @Size(max = 1000) String content) {}
}
