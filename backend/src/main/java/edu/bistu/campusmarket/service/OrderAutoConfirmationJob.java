package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.mapper.OrderFulfillmentMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("db")
@EnableScheduling
public class OrderAutoConfirmationJob {
    private static final Logger LOG = LoggerFactory.getLogger(OrderAutoConfirmationJob.class);
    private static final int BATCH_SIZE = 100;
    private final OrderFulfillmentMapper mapper;
    private final OrderFulfillmentService fulfillment;

    public OrderAutoConfirmationJob(OrderFulfillmentMapper mapper, OrderFulfillmentService fulfillment) {
        this.mapper = mapper;
        this.fulfillment = fulfillment;
    }

    @Scheduled(fixedDelayString = "${app.orders.auto-confirm-delay:PT1M}",
            initialDelayString = "${app.orders.auto-confirm-delay:PT1M}")
    public void confirmDueOrders() {
        LocalDateTime cutoff = mapper.autoConfirmationCutoff();
        long afterId = 0;
        List<Long> orderIds;
        do {
            orderIds = mapper.findOrdersDueForAutoConfirmation(afterId, cutoff, BATCH_SIZE);
            for (long orderId : orderIds) {
                try {
                    fulfillment.autoConfirm(orderId, cutoff);
                } catch (RuntimeException exception) {
                    LOG.warn("Automatic confirmation failed for order {}. It will be retried in a later scan.",
                            orderId, exception);
                }
                afterId = orderId;
            }
        } while (orderIds.size() == BATCH_SIZE);
    }
}
