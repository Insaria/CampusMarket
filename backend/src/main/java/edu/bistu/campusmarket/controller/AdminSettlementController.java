package edu.bistu.campusmarket.controller;

import edu.bistu.campusmarket.common.ApiResponse;
import edu.bistu.campusmarket.dto.AdminSettlementDtos.FeeReport;
import edu.bistu.campusmarket.dto.AdminSettlementDtos.GroupBy;
import edu.bistu.campusmarket.dto.AdminSettlementDtos.Page;
import edu.bistu.campusmarket.dto.AdminSettlementDtos.SellerMetrics;
import edu.bistu.campusmarket.security.ApiAccess;
import edu.bistu.campusmarket.service.AdminSettlementService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.OffsetDateTime;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("db")
@ApiAccess(ApiAccess.Requirement.ADMIN)
public class AdminSettlementController {
    private final AdminSettlementService service;

    public AdminSettlementController(AdminSettlementService service) {
        this.service = service;
    }

    @GetMapping("/api/admin/sellers/{sellerId}/metrics")
    public ApiResponse<SellerMetrics> seller(@PathVariable @Min(1) long sellerId) {
        return ApiResponse.success(service.sellerMetrics(sellerId));
    }

    @GetMapping("/api/admin/settlements/fees")
    public ApiResponse<FeeReport> fees(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) @Min(1) Long sellerId,
            @RequestParam(required = false) @Min(1) @Max(5) Integer levelCode,
            @RequestParam(required = false) GroupBy groupBy) {
        return ApiResponse.success(service.fees(from, to, sellerId, levelCode, groupBy));
    }

    @GetMapping("/api/admin/settlements")
    public ApiResponse<Page> details(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) @Min(1) Long sellerId,
            @RequestParam(required = false) @Min(1) @Max(5) Integer levelCode,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(service.details(from, to, sellerId, levelCode, page, size));
    }
}
