package com.example.userservice.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * @param count       how many users to generate
 * @param emailPrefix optional prefix for generated emails/names (defaults to "user")
 */
public record GenerateUsersRequest(
        @NotNull @Min(1) @Max(100_000) Integer count,
        String emailPrefix) {
}
