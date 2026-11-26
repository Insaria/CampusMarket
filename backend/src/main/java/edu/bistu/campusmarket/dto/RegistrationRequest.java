package edu.bistu.campusmarket.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegistrationRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String loginName,
        @NotBlank @Size(min = 8, max = 256) @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password,
        @NotBlank @Size(max = 64) String realName,
        @NotBlank @Size(max = 32) @Pattern(regexp = "\\+[1-9][0-9]{1,14}") String phone,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 64) String city,
        @NotBlank @Size(max = 16) @Pattern(regexp = "[A-Z][A-Z0-9_]*") String genderCode,
        @NotBlank @Pattern(regexp = "[0-9]{16}") @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String bankAccount,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9]{6}") @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String captcha) {

    public RegistrationRequest {
        loginName = new LoginAccountQuery(loginName).loginName();
    }

    @Override
    public String toString() {
        return "RegistrationRequest[redacted]";
    }
}
