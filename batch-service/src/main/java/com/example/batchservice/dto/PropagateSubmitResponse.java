package com.example.batchservice.dto;

import com.example.batchservice.entity.RequestStatus;

public record PropagateSubmitResponse(Long requestId, Long jobExecutionId, RequestStatus status,
                                      int totalItems, String statusUrl, String itemsUrl) {
}
