package com.example.batchservice.dto;

import com.example.batchservice.entity.IdentifierType;
import com.example.batchservice.entity.ItemStatus;
import com.example.batchservice.entity.PropagationItem;
import com.example.batchservice.entity.Stage;

import java.math.BigDecimal;
import java.time.Instant;

public record PropagationItemResponse(Long id, IdentifierType identifierType, String identifier, ItemStatus status,
                                      Long userId, String userEmail, Long orderId, BigDecimal orderAmount,
                                      Long paymentId, String paymentStatus, String transactionRef,
                                      Stage failedStage, String errorMessage, Instant processedAt) {

    public static PropagationItemResponse from(PropagationItem i) {
        return new PropagationItemResponse(i.getId(), i.getIdentifierType(), i.getIdentifier(), i.getStatus(),
                i.getUserId(), i.getUserEmail(), i.getOrderId(), i.getOrderAmount(), i.getPaymentId(),
                i.getPaymentStatus(), i.getTransactionRef(), i.getFailedStage(), i.getErrorMessage(),
                i.getProcessedAt());
    }
}
