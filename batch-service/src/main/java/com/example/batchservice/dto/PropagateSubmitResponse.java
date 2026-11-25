package com.example.batchservice.dto;

import com.example.batchservice.entity.RequestStatus;
import com.example.batchservice.entity.RequestType;

/**
 * @param totalItems     items to process; for BULK this is 0 until the job has selected the users
 * @param requestedCount BULK only: the count you asked for
 */
public record PropagateSubmitResponse(Long requestId, RequestType type, Long jobExecutionId, RequestStatus status,
                                      int totalItems, Integer requestedCount, String statusUrl, String itemsUrl) {
}
