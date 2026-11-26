package edu.bistu.campusmarket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public record LoginAccountQuery(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String loginName) {

    public LoginAccountQuery {
        if (loginName != null) {
            int start = 0;
            int end = loginName.length();
            while (start < end && loginName.charAt(start) == ' ') {
                start++;
            }
            while (end > start && loginName.charAt(end - 1) == ' ') {
                end--;
            }
            loginName = loginName.substring(start, end);
        }
    }

    public String canonicalLoginName() {
        return loginName.toLowerCase(Locale.ROOT);
    }
}
