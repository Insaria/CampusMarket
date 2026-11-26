package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.mapper.SettlementMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("db")
public class SettlementJob {
    private static final Logger LOG = LoggerFactory.getLogger(SettlementJob.class);
    private static final int BATCH_SIZE = 100;
    private final SettlementMapper mapper;
    private final SettlementService settlements;

    public SettlementJob(SettlementMapper mapper, SettlementService settlements) {
        this.mapper = mapper;
        this.settlements = settlements;
    }

    @Scheduled(fixedDelayString = "${app.orders.settlement-delay:PT1M}",
            initialDelayString = "${app.orders.settlement-delay:PT1M}")
    public void settleDueOrders() {
        long afterId = 0;
        List<Long> orderIds;
        do {
            orderIds = mapper.findDueOrders(afterId, BATCH_SIZE);
            for (long orderId : orderIds) {
                try {
                    settlements.settle(orderId);
                } catch (RuntimeException exception) {
                    LOG.warn("Settlement failed for order {}. It remains pending.", orderId, exception);
                }
                afterId = orderId;
            }
        } while (orderIds.size() == BATCH_SIZE);
    }
}
