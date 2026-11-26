package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.dto.AdminUserDtos.AccountChange;
import edu.bistu.campusmarket.dto.AdminUserDtos.AccountStatus;
import edu.bistu.campusmarket.dto.AdminUserDtos.ProfileUpdate;
import edu.bistu.campusmarket.dto.AdminUserDtos.Recharge;
import edu.bistu.campusmarket.dto.AdminUserDtos.RechargeReceipt;
import edu.bistu.campusmarket.dto.AdminUserDtos.RegistrationStatus;
import edu.bistu.campusmarket.dto.AdminUserDtos.ReviewDecision;
import edu.bistu.campusmarket.dto.AdminUserDtos.UserPage;
import edu.bistu.campusmarket.dto.AdminUserDtos.UserView;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.AdminUserMapper;
import edu.bistu.campusmarket.mapper.SellerProductMapper.GeneratedId;
import java.util.List;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class AdminUserService {
    private final AdminUserMapper mapper;

    public AdminUserService(AdminUserMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public UserPage list(String loginName, RegistrationStatus registration, AccountStatus status, int page, int size) {
        String pattern = loginName == null || loginName.isBlank() ? null
                : "%" + loginName.strip().toLowerCase(Locale.ROOT)
                        .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        long offset = (page - 1L) * size;
        long total = mapper.count(pattern, registration, status);
        List<UserView> items = offset < total ? mapper.findPage(pattern, registration, status, offset, size) : List.of();
        return new UserPage(items, total, page, size);
    }

    public UserView find(long id) {
        return requireUser(mapper.findById(id));
    }

    public long pendingCount() {
        return mapper.countPendingReview();
    }

    @Transactional
    public UserView review(long actorId, long targetId, ReviewDecision decision) {
        UserView target = lockForChange(actorId, targetId);
        if (!"USER".equals(target.roleCode())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ADMIN_REVIEW_FORBIDDEN", "不能审核管理员账号");
        }
        if (target.accountStatus() == AccountStatus.DELETED || target.registrationStatus() != RegistrationStatus.PENDING
                || mapper.review(targetId, decision) != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "REGISTRATION_ALREADY_REVIEWED", "该账号已审核");
        }
        audit(actorId, targetId, decision == ReviewDecision.APPROVED ? "USER_REGISTRATION_APPROVED"
                : "USER_REGISTRATION_REJECTED");
        return find(targetId);
    }

    @Transactional
    public UserView changeStatus(long actorId, long targetId, AccountChange change) {
        UserView target = lockForChange(actorId, targetId);
        if (!"USER".equals(target.roleCode())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ADMIN_STATUS_PROTECTED", "不能通过用户管理停用管理员");
        }
        if (target.accountStatus() == AccountStatus.DELETED) {
            throw new BusinessException(HttpStatus.CONFLICT, "ACCOUNT_DELETED", "不能更改已删除账号");
        }
        if (target.accountStatus().name().equals(change.name())) {
            return target;
        }
        if (mapper.changeStatus(targetId, change) != 1) {
            throw new IllegalStateException("Account status update did not affect one user");
        }
        audit(actorId, targetId, change == AccountChange.ENABLED ? "USER_ENABLED" : "USER_DISABLED");
        return find(targetId);
    }

    @Transactional
    public RechargeReceipt recharge(long actorId, long targetId, Recharge input) {
        UserView target = lockForChange(actorId, targetId);
        if (!"USER".equals(target.roleCode()) || target.accountStatus() == AccountStatus.DELETED) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECHARGE_TARGET_INVALID", "只能为未删除的普通用户充值");
        }
        mapper.ensureWallet(targetId);
        var wallet = mapper.lockWallet(targetId);
        var existing = mapper.rechargeEntry(wallet.id(), input.requestKey());
        if (existing != null) {
            if (existing.amount().compareTo(input.amount()) != 0) {
                throw new BusinessException(HttpStatus.CONFLICT, "RECHARGE_KEY_CONFLICT", "充值请求键已用于其他金额");
            }
            return new RechargeReceipt(existing.id(), existing.amount(), wallet.balance());
        }
        if (mapper.credit(wallet.id(), input.amount()) != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "WALLET_BALANCE_LIMIT", "充值后余额超出允许范围");
        }
        GeneratedId key = new GeneratedId();
        if (mapper.recordRecharge(wallet.id(), input.amount(), input.requestKey(), key) != 1 || key.getId() == null
                || mapper.auditRecharge(actorId, targetId, key.getId(), input.amount(), input.reason().strip()) != 1) {
            throw new IllegalStateException("Recharge ledger or audit could not be recorded");
        }
        return new RechargeReceipt(key.getId(), input.amount(), wallet.balance().add(input.amount()));
    }

    @Transactional
    public UserView updateProfile(long actorId, long targetId, ProfileUpdate profile) {
        UserView target = lockForChange(actorId, targetId);
        requireEditableUser(target);
        int domainStart = profile.email().lastIndexOf('@') + 1;
        String email = profile.email().substring(0, domainStart)
                + profile.email().substring(domainStart).toLowerCase(Locale.ROOT);
        String city = profile.city() == null || profile.city().isBlank() ? null : profile.city().strip();
        mapper.updateProfile(targetId, new ProfileUpdate(profile.realName().strip(), profile.phone(), email,
                city, profile.genderCode()));
        audit(actorId, targetId, "USER_PROFILE_UPDATED");
        return find(targetId);
    }

    @Transactional
    public UserView delete(long actorId, long targetId) {
        UserView target = lockForChange(actorId, targetId);
        if ("USER".equals(target.roleCode()) && target.accountStatus() == AccountStatus.DELETED) {
            return target;
        }
        requireEditableUser(target);
        if (mapper.deleteUnusedAccount(targetId) != 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "ACCOUNT_DELETE_BLOCKED",
                    "请先停用账号。已有卖家档案、结账记录或非零余额的账号需保留，请使用停用功能");
        }
        audit(actorId, targetId, "USER_DELETED");
        return find(targetId);
    }

    private void requireEditableUser(UserView target) {
        if (!"USER".equals(target.roleCode()) || target.accountStatus() == AccountStatus.DELETED) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "USER_NOT_EDITABLE", "只能操作尚未删除的普通用户");
        }
    }

    private UserView lockForChange(long actorId, long targetId) {
        UserView first = mapper.lockById(Math.min(actorId, targetId));
        UserView second = actorId == targetId ? first : mapper.lockById(Math.max(actorId, targetId));
        UserView actor = actorId <= targetId ? first : second;
        if (actor == null || !"ADMIN".equals(actor.roleCode()) || actor.accountStatus() != AccountStatus.ENABLED
                || actor.registrationStatus() != RegistrationStatus.APPROVED) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "权限不足");
        }
        return requireUser(targetId <= actorId ? first : second);
    }

    private UserView requireUser(UserView user) {
        if (user == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在");
        }
        return user;
    }

    private void audit(long actorId, long targetId, String action) {
        if (mapper.audit(actorId, action, targetId) != 1) {
            throw new IllegalStateException("Audit insert did not affect one row");
        }
    }
}
