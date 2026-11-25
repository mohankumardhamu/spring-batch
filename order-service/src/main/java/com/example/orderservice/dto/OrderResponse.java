package com.example.orderservice.dto;

import com.example.orderservice.entity.Order;
import com.example.orderservice.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderResponse(Long id, Long userId, String productName, Integer quantity, BigDecimal unitPrice,
                            BigDecimal totalAmount, OrderStatus status, Instant createdAt, Instant updatedAt) {

    public static OrderResponse from(Order o) {
        return new OrderResponse(o.getId(), o.getUserId(), o.getProductName(), o.getQuantity(), o.getUnitPrice(),
                o.getTotalAmount(), o.getStatus(), o.getCreatedAt(), o.getUpdatedAt());
    }
}
