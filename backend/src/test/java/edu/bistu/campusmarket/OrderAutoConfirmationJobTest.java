package edu.bistu.campusmarket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import edu.bistu.campusmarket.mapper.OrderFulfillmentMapper;
import edu.bistu.campusmarket.service.OrderAutoConfirmationJob;
import edu.bistu.campusmarket.service.OrderFulfillmentService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class OrderAutoConfirmationJobTest {
    @Test
    void registersScheduledTaskInDatabaseProfile() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("db");
            context.registerBean(OrderFulfillmentMapper.class, () -> mock(OrderFulfillmentMapper.class));
            context.registerBean(OrderFulfillmentService.class, () -> mock(OrderFulfillmentService.class));
            context.register(OrderAutoConfirmationJob.class);
            context.refresh();

            var scheduler = context.getBean(ScheduledAnnotationBeanPostProcessor.class);
            assertEquals(1, scheduler.getScheduledTasks().size());
        }
    }

    @Test
    void scansNextBatchEvenWhenAnOrderFails() {
        var mapper = mock(OrderFulfillmentMapper.class);
        var fulfillment = mock(OrderFulfillmentService.class);
        var job = new OrderAutoConfirmationJob(mapper, fulfillment);
        var cutoff = LocalDateTime.parse("2026-11-12T08:30:00.125");
        List<Long> firstBatch = LongStream.rangeClosed(1, 100).boxed().toList();
        when(mapper.autoConfirmationCutoff()).thenReturn(cutoff);
        when(mapper.findOrdersDueForAutoConfirmation(0, cutoff, 100)).thenReturn(firstBatch);
        when(mapper.findOrdersDueForAutoConfirmation(100, cutoff, 100)).thenReturn(List.of(101L));
        when(fulfillment.autoConfirm(1L, cutoff)).thenThrow(new IllegalStateException("Test write failure"));

        job.confirmDueOrders();

        var scan = inOrder(mapper, fulfillment);
        scan.verify(mapper).autoConfirmationCutoff();
        scan.verify(mapper).findOrdersDueForAutoConfirmation(0, cutoff, 100);
        for (long id : firstBatch) {
            scan.verify(fulfillment).autoConfirm(id, cutoff);
        }
        scan.verify(mapper).findOrdersDueForAutoConfirmation(100, cutoff, 100);
        scan.verify(fulfillment).autoConfirm(101L, cutoff);
        scan.verifyNoMoreInteractions();
    }
}
