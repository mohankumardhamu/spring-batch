package com.example.batchservice.dto;

import com.example.batchservice.entity.RequestStatus;
import com.example.batchservice.entity.RequestType;

import java.time.Instant;

public record PropagationStatusResponse(
        Long requestId,
        RequestType type,
        RequestStatus status,
        Long jobExecutionId,
        String jobName,
        String jobStatus,
        String jobExitCode,
        Instant jobStartTime,
        Instant jobEndTime,
        Integer requestedCount,
        int totalItems,
        int attempts,
        long succeeded,
        long failed,
        long pending,
        Long durationMs,
        Long usersPerSecond,
        Instant createdAt,
        Instant updatedAt) {
}
