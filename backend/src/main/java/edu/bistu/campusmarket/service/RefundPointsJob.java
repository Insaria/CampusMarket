package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.mapper.RefundPointsMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("db")
public class RefundPointsJob {
    private static final Logger LOG = LoggerFactory.getLogger(RefundPointsJob.class);
    private static final int BATCH_SIZE = 100;
    private final RefundPointsMapper mapper;
    private final RefundPointsService refunds;

    public RefundPointsJob(RefundPointsMapper mapper, RefundPointsService refunds) {
        this.mapper = mapper;
        this.refunds = refunds;
    }

    @Scheduled(fixedDelayString = "${app.orders.refund-points-delay:PT1M}",
            initialDelayString = "${app.orders.refund-points-delay:PT1M}")
    public void processDueRefunds() {
        long afterId = 0;
        List<Long> refundIds;
        do {
            refundIds = mapper.findCandidates(afterId, BATCH_SIZE);
            for (long refundId : refundIds) {
                try {
                    refunds.process(refundId);
                } catch (RuntimeException exception) {
                    LOG.warn("Points processing failed for refund {}. It remains pending.", refundId, exception);
                }
                afterId = refundId;
            }
        } while (refundIds.size() == BATCH_SIZE);
    }
}
