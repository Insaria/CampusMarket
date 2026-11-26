package edu.bistu.campusmarket.dto;

public record AuthenticatedUser(Long userId, String roleCode) {
    public static final String SESSION_ATTRIBUTE = AuthenticatedUser.class.getName();
}
