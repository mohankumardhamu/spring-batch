package com.example.paymentservice.dto;

import com.example.paymentservice.entity.Payment;
import com.example.paymentservice.entity.PaymentMethod;
import com.example.paymentservice.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(Long id, Long orderId, Long userId, BigDecimal amount, PaymentMethod method,
                              PaymentStatus status, String transactionRef, Instant createdAt, Instant updatedAt) {

    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(p.getId(), p.getOrderId(), p.getUserId(), p.getAmount(), p.getMethod(),
                p.getStatus(), p.getTransactionRef(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
