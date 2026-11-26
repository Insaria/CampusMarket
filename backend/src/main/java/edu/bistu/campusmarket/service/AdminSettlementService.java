package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.dto.AdminSettlementDtos.FeeReport;
import edu.bistu.campusmarket.dto.AdminSettlementDtos.Filter;
import edu.bistu.campusmarket.dto.AdminSettlementDtos.Group;
import edu.bistu.campusmarket.dto.AdminSettlementDtos.GroupBy;
import edu.bistu.campusmarket.dto.AdminSettlementDtos.Page;
import edu.bistu.campusmarket.dto.AdminSettlementDtos.SellerMetrics;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.AdminSettlementMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class AdminSettlementService {
    private final AdminSettlementMapper mapper;
    private final AdminSellerService sellers;
    private final ReviewService reviews;

    public AdminSettlementService(AdminSettlementMapper mapper, AdminSellerService sellers, ReviewService reviews) {
        this.mapper = mapper;
        this.sellers = sellers;
        this.reviews = reviews;
    }

    @Transactional(readOnly = true)
    public FeeReport fees(OffsetDateTime from, OffsetDateTime to, Long sellerId, Integer levelCode, GroupBy groupBy) {
        Filter filter = filter(from, to, sellerId, levelCode);
        var totals = mapper.totals(filter);
        List<Group> groups = groupBy == null ? List.of() : switch (groupBy) {
            case SELLER -> mapper.bySeller(filter);
            case LEVEL -> mapper.byLevel(filter);
        };
        return new FeeReport(totals, groups);
    }

    @Transactional(readOnly = true)
    public Page details(OffsetDateTime from, OffsetDateTime to, Long sellerId, Integer levelCode, int page, int size) {
        Filter filter = filter(from, to, sellerId, levelCode);
        long total = mapper.count(filter);
        long offset = (page - 1L) * size;
        return new Page(offset < total ? mapper.page(filter, size, offset) : List.of(), total, page, size);
    }

    @Transactional(readOnly = true)
    public SellerMetrics sellerMetrics(long sellerId) {
        var seller = sellers.find(sellerId);
        return new SellerMetrics(seller, mapper.totals(new Filter(null, null, sellerId, null)),
                reviews.sellerSatisfaction(seller.userId()));
    }

    private Filter filter(OffsetDateTime from, OffsetDateTime to, Long sellerId, Integer levelCode) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_SETTLEMENT_RANGE", "开始时间须早于结束时间");
        }
        return new Filter(from == null ? null : from.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(),
                to == null ? null : to.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(), sellerId, levelCode);
    }
}
