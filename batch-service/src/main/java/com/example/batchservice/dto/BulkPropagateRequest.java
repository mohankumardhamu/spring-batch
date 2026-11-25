package com.example.batchservice.dto;

/**
 * @param count how many not-yet-propagated users to select from user-service and propagate
 */
public record BulkPropagateRequest(Integer count) {
}
