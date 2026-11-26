package edu.bistu.campusmarket.controller;

import edu.bistu.campusmarket.common.ApiResponse;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.dto.NotificationDtos.Message;
import edu.bistu.campusmarket.dto.NotificationDtos.Page;
import edu.bistu.campusmarket.dto.NotificationDtos.Unread;
import edu.bistu.campusmarket.security.ApiAccess;
import edu.bistu.campusmarket.security.CurrentUserService;
import edu.bistu.campusmarket.service.NotificationService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("db")
@ApiAccess(ApiAccess.Requirement.AUTHENTICATED)
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page>> list(
            @RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return response(service.list(user.userId(), unreadOnly, page, size));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<Unread>> unread(@RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user) {
        return response(new Unread(service.unread(user.userId())));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Message>> detail(@RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable @Min(1) long id) {
        return response(service.detail(user.userId(), id));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<ApiResponse<Message>> read(@RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable @Min(1) long id) {
        return response(service.read(user.userId(), id));
    }

    @PatchMapping("/read-all")
    public ResponseEntity<ApiResponse<Integer>> readAll(@RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser user) {
        return response(service.readAll(user.userId()));
    }

    private static <T> ResponseEntity<ApiResponse<T>> response(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(value));
    }
}
