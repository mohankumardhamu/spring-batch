package com.example.batchservice.dto;

import java.util.List;

/**
 * Users to propagate, identified by id and/or email. At least one identifier is required.
 * For each user the batch creates a sample order and a matching payment.
 */
public record PropagateRequest(List<Long> userIds, List<String> emails) {
}
