package com.example.paymentservice.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * @param userId      / orderId: the filter that was applied (null when not filtered)
 * @param byStatus    every status, including those with a count of 0
 * @param totalAmount sum of all matching payment amounts
 */
public record CountResponse(String resource, Long userId, Long orderId, long total,
                            Map<String, Long> byStatus, BigDecimal totalAmount) {
}
