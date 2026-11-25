package com.example.userservice.dto;

import com.example.userservice.entity.User;

import java.time.Instant;

public record UserResponse(Long id, String name, String email, String phone, Instant createdAt, Instant updatedAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getPhone(),
                user.getCreatedAt(), user.getUpdatedAt());
    }
}
