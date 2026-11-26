package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.dto.SellerDtos.Application;
import edu.bistu.campusmarket.dto.SellerDtos.SellerView;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.SellerMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@Profile("db")
public class SellerService {
    private final SellerMapper mapper;
    private final PrivateSellerDocuments documents;

    public SellerService(SellerMapper mapper, PrivateSellerDocuments documents) {
        this.mapper = mapper;
        this.documents = documents;
    }

    public SellerView find(long userId) {
        SellerView seller = mapper.findByUserId(userId);
        if (seller == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "SELLER_NOT_FOUND", "尚未创建卖家档案");
        }
        return seller;
    }

    @Transactional
    public SellerView createPersonal(long userId, Application input) {
        checkApplicant(userId, false);
        try {
            if (mapper.insertPersonal(userId, input.shopName().strip(), input.shopDescription()) != 1) {
                throw new IllegalStateException("Seller insert did not create one profile");
            }
        } catch (DuplicateKeyException exception) {
            throw alreadyExists();
        }
        return find(userId);
    }

    @Transactional
    public SellerView applyBusiness(long userId, Application input, MultipartFile license, MultipartFile identity) {
        checkApplicant(userId, true);
        List<String> staged = new ArrayList<>(2);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    staged.forEach(documents::discard);
                }
            }
        });
        staged.add(documents.store(license));
        staged.add(documents.store(identity));
        try {
            if (mapper.insertBusiness(userId, input.shopName().strip(), input.shopDescription(),
                    staged.get(0), staged.get(1)) != 1) {
                throw new IllegalStateException("Seller insert did not create one profile");
            }
        } catch (DuplicateKeyException exception) {
            throw alreadyExists();
        }
        return find(userId);
    }

    private void checkApplicant(long userId, boolean business) {
        var applicant = mapper.lockApplicant(userId);
        if (applicant == null || !"USER".equals(applicant.roleCode())
                || !"APPROVED".equals(applicant.registrationStatus()) || !"ENABLED".equals(applicant.accountStatus())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "SELLER_APPLICANT_NOT_ELIGIBLE", "账号不具备卖家申请资格");
        }
        if (mapper.findByUserId(userId) != null) {
            throw alreadyExists();
        }
        if (business && !Integer.valueOf(1).equals(applicant.profileComplete())) {
            throw new BusinessException(HttpStatus.CONFLICT, "SELLER_PROFILE_INCOMPLETE", "请先补齐个人资料");
        }
    }

    private BusinessException alreadyExists() {
        return new BusinessException(HttpStatus.CONFLICT, "SELLER_ALREADY_EXISTS", "该用户已有卖家档案");
    }
}
