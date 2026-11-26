package edu.bistu.campusmarket.dto;

public record OwnProfile(String loginName, String realName, String phone, String email, String city,
        String genderCode, String registrationStatus, String accountStatus) {
}
