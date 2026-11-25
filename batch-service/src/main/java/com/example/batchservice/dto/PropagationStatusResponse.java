package com.example.batchservice.dto;

import com.example.batchservice.entity.RequestStatus;

import java.time.Instant;

public record PropagationStatusResponse(
        Long requestId,
        RequestStatus status,
        Long jobExecutionId,
        String jobStatus,
        String jobExitCode,
        Instant jobStartTime,
        Instant jobEndTime,
        int totalItems,
        int attempts,
        long succeeded,
        long failed,
        long pending,
        Instant createdAt,
        Instant updatedAt) {
}
