package edu.bistu.campusmarket.service;

import static edu.bistu.campusmarket.mapper.OrderPointsMapper.REDEMPTION_KIND;
import static edu.bistu.campusmarket.mapper.OrderPointsMapper.REWARD_KIND;

import edu.bistu.campusmarket.dto.ReturnRequestDtos.Status;
import edu.bistu.campusmarket.mapper.OrderFulfillmentMapper;
import edu.bistu.campusmarket.mapper.RefundMapper;
import edu.bistu.campusmarket.mapper.RefundPointsMapper;
import edu.bistu.campusmarket.mapper.RefundPointsMapper.Account;
import edu.bistu.campusmarket.mapper.RefundPointsMapper.Entry;
import edu.bistu.campusmarket.mapper.RefundPointsMapper.Refund;
import edu.bistu.campusmarket.mapper.ReturnRequestMapper;
import edu.bistu.campusmarket.mapper.SettlementMapper;
import edu.bistu.campusmarket.mapper.SettlementMapper.Order;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class RefundPointsService {
    private static final String CASH_REFUNDED = "CASH_REFUNDED";
    private static final String PENDING_POINTS = "PENDING_POINTS";

    private final RefundPointsMapper mapper;
    private final SettlementMapper orders;
    private final ReturnRequestMapper requests;
    private final RefundMapper cashRefunds;
    private final OrderFulfillmentMapper ownership;
    private final NotificationService notifications;

    public RefundPointsService(RefundPointsMapper mapper, SettlementMapper orders, ReturnRequestMapper requests,
            RefundMapper cashRefunds, OrderFulfillmentMapper ownership, NotificationService notifications) {
        this.mapper = mapper;
        this.orders = orders;
        this.requests = requests;
        this.cashRefunds = cashRefunds;
        this.ownership = ownership;
        this.notifications = notifications;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void process(long refundId) {
        var target = mapper.findTarget(refundId);
        if (target == null) {
            return;
        }
        Order order = orders.lockOrder(target.orderId());
        var request = requests.lockRequest(target.orderId(), target.requestId());
        Refund refund = mapper.lockRefund(refundId, target.requestId());
        if (refund == null || refund.completedAt() != null
                || (!CASH_REFUNDED.equals(refund.refundStatus()) && !PENDING_POINTS.equals(refund.refundStatus()))) {
            return;
        }
        if (order == null || !"COMPLETED".equals(order.orderStatus())
                || request == null || !request.active() || !Status.isApproved(request.requestStatus())) {
            throw new IllegalStateException("Refund has no eligible approved return and completed order");
        }
        validateFunding(order, refund);

        Long buyerId = ownership.findBuyerId(order.batchId());
        if (buyerId == null) {
            throw new IllegalStateException("Refund order has no buyer");
        }
        Account account = mapper.lockAccount(buyerId);
        var pending = reconcile(order.id(), refundId, refund, account);
        List<Entry> restorations = pending.stream().filter(e -> REDEMPTION_KIND.equals(e.entryKind())).toList();
        List<Entry> reversals = pending.stream().filter(e -> REWARD_KIND.equals(e.entryKind())).toList();
        long toRestore = total(restorations);
        long toReverse = total(reversals);
        long balance = account == null ? 0 : account.balance();

        if (toRestore > 0) {
            requireOne(mapper.restoreBalance(account.id(), toRestore), "Points restoration balance update failed");
            recordAdjustments(restorations, refundId);
            balance = Math.addExact(balance, toRestore);
        }
        long restored = Math.addExact(refund.pointsRestored(), toRestore);
        if (balance < toReverse) {
            String failure = "POINTS_REWARD_BALANCE_INSUFFICIENT;remaining=" + toReverse;
            if (toRestore > 0 || !failure.equals(refund.failureReason())) {
                requireOne(mapper.recordProgress(refundId, restored, refund.pointsReversed(), failure, false),
                        "Pending points refund was not recorded");
            }
            return;
        }
        if (toReverse > 0) {
            requireOne(mapper.reverseBalance(account.id(), toReverse), "Reward points balance update failed");
            recordAdjustments(reversals, refundId);
        }
        requireOne(mapper.recordProgress(refundId, restored, Math.addExact(refund.pointsReversed(), toReverse), null, true),
                "Completed points refund was not recorded");
        notifications.emit(NotificationService.Event.REFUND_COMPLETED, order.id(), refundId);
    }

    private void validateFunding(Order order, Refund refund) {
        if (CASH_REFUNDED.equals(refund.refundStatus())) {
            if (refund.buyerCreditEntryId() == null || refund.cashRefundAmount().signum() <= 0
                    || refund.cashRefundAmount().compareTo(order.walletDebitAmount()) != 0) {
                throw new IllegalStateException("Cash refund evidence does not match the original order");
            }
        } else if (refund.buyerCreditEntryId() != null || refund.cashRefundAmount().signum() != 0
                || !cashRefunds.hasPointsOnlyFunding(order.id())) {
            throw new IllegalStateException("Points-only refund does not match the original order");
        }
    }

    private List<Entry> reconcile(long orderId, long refundId, Refund refund, Account account) {
        var pending = new LinkedHashMap<Long, Entry>();
        long redeemed = 0;
        for (Entry source : mapper.findOriginals(orderId)) {
            boolean redemption = REDEMPTION_KIND.equals(source.entryKind()) && "DEBIT".equals(source.direction());
            boolean reward = REWARD_KIND.equals(source.entryKind()) && "CREDIT".equals(source.direction());
            if (account == null || !account.id().equals(source.accountId()) || source.orderId() != orderId
                    || source.refundId() != null || source.originalEntryId() != null || source.quantity() <= 0
                    || (!redemption && !reward)) {
                throw new IllegalStateException("Unrecognized or mismatched original order points entry");
            }
            if (redemption) {
                redeemed = Math.addExact(redeemed, source.quantity());
            }
            pending.put(source.id(), source);
        }
        if (redeemed != mapper.pointsUsed(orderId)) {
            throw new IllegalStateException("Order points snapshot does not match original redemption entries");
        }

        long restored = 0;
        long reversed = 0;
        for (Entry applied : mapper.findAdjustments(orderId, refundId)) {
            Entry source = pending.remove(applied.originalEntryId());
            if (source == null || !adjustment(source, refundId, applied.id()).equals(applied)) {
                throw new IllegalStateException("Duplicate or mismatched refund points entry");
            }
            if (REDEMPTION_KIND.equals(source.entryKind())) {
                restored = Math.addExact(restored, source.quantity());
            } else {
                reversed = Math.addExact(reversed, source.quantity());
            }
        }
        if (restored != refund.pointsRestored() || reversed != refund.pointsReversed()) {
            throw new IllegalStateException("Refund points counters do not match persisted adjustments");
        }
        return List.copyOf(pending.values());
    }

    private long total(List<Entry> entries) {
        return entries.stream().mapToLong(Entry::quantity).reduce(0L, Math::addExact);
    }

    private void recordAdjustments(List<Entry> entries, long refundId) {
        for (Entry source : entries) {
            requireOne(mapper.recordAdjustment(adjustment(source, refundId, null)), "Refund points entry was not recorded");
        }
    }

    private Entry adjustment(Entry source, long refundId, Long id) {
        boolean restoration = REDEMPTION_KIND.equals(source.entryKind());
        return new Entry(id, source.accountId(), source.orderId(), refundId, source.id(),
                restoration ? "REFUND_POINTS_RESTORE" : "REFUND_POINTS_REVERSE",
                restoration ? "CREDIT" : "DEBIT", source.quantity(), "REFUND_POINTS:" + source.id());
    }

    private void requireOne(int changed, String message) {
        if (changed != 1) {
            throw new IllegalStateException(message);
        }
    }
}
