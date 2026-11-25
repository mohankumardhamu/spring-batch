package com.example.userservice.dto;

import java.util.List;

public record GenerateUsersResponse(
        int requested,
        int created,
        int failed,
        long durationMs,
        String threadModel,
        List<String> sampleErrors) {
}
