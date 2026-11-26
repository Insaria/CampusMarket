package edu.bistu.campusmarket.mapper;

import edu.bistu.campusmarket.dto.AdminSettlementDtos.Detail;
import edu.bistu.campusmarket.dto.AdminSettlementDtos.Filter;
import edu.bistu.campusmarket.dto.AdminSettlementDtos.Group;
import edu.bistu.campusmarket.dto.AdminSettlementDtos.Totals;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AdminSettlementMapper {
    String TOTALS = """
            COUNT(*) AS settlement_count,
            COALESCE(SUM(s.released_amount), CAST(0 AS DECIMAL(14,2))) AS settled_amount,
            COALESCE(SUM(s.fee_amount), CAST(0 AS DECIMAL(14,2))) AS fee_amount
            """;
    String MATCHING = """
            FROM settlements s
            JOIN escrows e ON e.id = s.escrow_id
            JOIN merchant_orders o ON o.id = e.order_id
            WHERE s.settled_at IS NOT NULL
            <if test="filter.fromInclusive != null">AND s.settled_at &gt;= #{filter.fromInclusive}</if>
            <if test="filter.toExclusive != null">AND s.settled_at &lt; #{filter.toExclusive}</if>
            <if test="filter.sellerId != null">AND o.merchant_id = #{filter.sellerId}</if>
            <if test="filter.levelCode != null">AND s.level_code_snapshot = #{filter.levelCode}</if>
            """;

    @Select("<script>SELECT " + TOTALS + MATCHING + "</script>")
    Totals totals(@Param("filter") Filter filter);

    @Select("<script>SELECT o.merchant_id AS seller_id, NULL AS level_code, " + TOTALS + MATCHING
            + " GROUP BY o.merchant_id ORDER BY o.merchant_id</script>")
    List<Group> bySeller(@Param("filter") Filter filter);

    @Select("<script>SELECT NULL AS seller_id, s.level_code_snapshot AS level_code, " + TOTALS + MATCHING
            + " GROUP BY s.level_code_snapshot ORDER BY s.level_code_snapshot</script>")
    List<Group> byLevel(@Param("filter") Filter filter);

    @Select("<script>SELECT COUNT(*) " + MATCHING + "</script>")
    long count(@Param("filter") Filter filter);

    @Select("""
            <script>SELECT s.id AS settlement_id, o.id AS order_id, o.merchant_id AS seller_id,
                s.level_code_snapshot, s.fee_rate_snapshot, s.fee_rate_applied_at, s.fee_base_amount,
                s.released_amount AS settled_amount, s.fee_amount, s.seller_net_amount, s.settled_at
            """ + MATCHING + " ORDER BY s.settled_at DESC, s.id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<Detail> page(@Param("filter") Filter filter, @Param("size") int size, @Param("offset") long offset);
}
