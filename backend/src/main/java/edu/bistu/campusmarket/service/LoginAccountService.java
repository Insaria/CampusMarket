package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.dto.LoginAccountQuery;
import edu.bistu.campusmarket.dto.OwnProfile;
import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.UserAccountMapper;
import edu.bistu.campusmarket.mapper.UserAccountMapper.LoginAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

@Service
@Profile("db")
@Validated
public class LoginAccountService {
    private final UserAccountMapper mapper;

    public LoginAccountService(UserAccountMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<LoginAccount> findForAuthentication(@NotNull @Valid LoginAccountQuery query) {
        return Optional.ofNullable(mapper.findByLoginName(query.canonicalLoginName()));
    }

    public OwnProfile requireOwnProfile(long userId) {
        OwnProfile profile = mapper.findOwnProfile(userId);
        if (profile == null) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCOUNT_UNAVAILABLE", "账号不可用");
        }
        return profile;
    }
}
