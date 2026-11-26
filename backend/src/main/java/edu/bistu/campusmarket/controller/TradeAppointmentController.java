package edu.bistu.campusmarket.controller;

import edu.bistu.campusmarket.common.ApiResponse;
import edu.bistu.campusmarket.dto.AuthenticatedUser;
import edu.bistu.campusmarket.dto.TradeAppointmentDtos.Acceptance;
import edu.bistu.campusmarket.dto.TradeAppointmentDtos.Proposal;
import edu.bistu.campusmarket.dto.TradeAppointmentDtos.View;
import edu.bistu.campusmarket.security.ApiAccess;
import edu.bistu.campusmarket.security.CurrentUserService;
import edu.bistu.campusmarket.service.TradeAppointmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("db")
@ApiAccess(ApiAccess.Requirement.USER)
@RequestMapping("/api/orders/{orderId}/appointment")
public class TradeAppointmentController {
    private final TradeAppointmentService service;

    public TradeAppointmentController(TradeAppointmentService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<View> pending(@RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser actor,
            @PathVariable @Min(1) long orderId) {
        return ApiResponse.success(service.pending(actor.userId(), orderId));
    }

    @PostMapping
    public ApiResponse<View> propose(@RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser actor,
            @PathVariable @Min(1) long orderId, @Valid @RequestBody Proposal input) {
        return ApiResponse.success(service.propose(actor.userId(), orderId, input));
    }

    @PostMapping("/accept")
    public ApiResponse<Void> accept(@RequestAttribute(CurrentUserService.REQUEST_ATTRIBUTE) AuthenticatedUser actor,
            @PathVariable @Min(1) long orderId, @Valid @RequestBody Acceptance input) {
        service.accept(actor.userId(), orderId, input.proposalId());
        return ApiResponse.success(null);
    }
}
