package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.dto.AccountFinanceDtos.PointsView;
import edu.bistu.campusmarket.dto.AccountFinanceDtos.WalletView;
import edu.bistu.campusmarket.mapper.OrderPointsMapper;
import edu.bistu.campusmarket.mapper.WalletPaymentMapper;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class AccountFinanceService {
    private final WalletPaymentMapper wallets;
    private final OrderPointsMapper points;

    public AccountFinanceService(WalletPaymentMapper wallets, OrderPointsMapper points) {
        this.wallets = wallets;
        this.points = points;
    }

    @Transactional(readOnly = true)
    public WalletView wallet(long userId, int page, int size) {
        var wallet = wallets.findWallet(userId);
        if (wallet == null) {
            return new WalletView(null, List.of(), 0, page, size);
        }
        long total = wallets.countEntries(wallet.id());
        long offset = (page - 1L) * size;
        return new WalletView(wallet.balance(), offset < total ? wallets.entries(wallet.id(), size, offset) : List.of(),
                total, page, size);
    }

    @Transactional(readOnly = true)
    public PointsView points(long userId, int page, int size) {
        var account = points.findAccount(userId);
        if (account == null) {
            return new PointsView(null, List.of(), 0, page, size);
        }
        long total = points.countEntries(account.id());
        long offset = (page - 1L) * size;
        return new PointsView(account.balance(), offset < total ? points.entries(account.id(), size, offset) : List.of(),
                total, page, size);
    }
}
