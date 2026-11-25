package com.example.orderservice.dto;

import com.example.orderservice.entity.OrderStatus;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/**
 * @param status optional; defaults to CREATED on create and is left unchanged on update when omitted
 */
public record OrderRequest(
        @NotNull @Positive Long userId,
        @NotBlank @Size(max = 150) String productName,
        @NotNull @Min(1) @Max(10_000) Integer quantity,
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 10, fraction = 2) BigDecimal unitPrice,
        OrderStatus status) {
}
