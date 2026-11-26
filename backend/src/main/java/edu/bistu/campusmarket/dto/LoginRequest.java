package edu.bistu.campusmarket.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class LoginRequest {
    @Valid
    private final LoginAccountQuery accountQuery;

    @NotBlank
    @Size(max = 256)
    private final String password;

    @JsonCreator
    public LoginRequest(@JsonProperty("loginName") String loginName,
            @JsonProperty(value = "password", access = JsonProperty.Access.WRITE_ONLY) String password) {
        this.accountQuery = new LoginAccountQuery(loginName);
        this.password = password;
    }

    public LoginAccountQuery accountQuery() {
        return accountQuery;
    }

    public String password() {
        return password;
    }
}
