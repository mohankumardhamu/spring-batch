package com.example.paymentservice.dto;

import com.example.paymentservice.entity.PaymentMethod;
import com.example.paymentservice.entity.PaymentStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * @param status optional; defaults to PENDING on create and is left unchanged on update when omitted
 */
public record PaymentRequest(
        @NotNull @Positive Long orderId,
        @NotNull @Positive Long userId,
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
        @NotNull PaymentMethod method,
        PaymentStatus status) {
}
