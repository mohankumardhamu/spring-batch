package com.example.orderservice.dto;

import java.util.Map;

/**
 * @param userId   the filter that was applied, or null when counting all orders
 * @param byStatus every status, including those with a count of 0
 */
public record CountResponse(String resource, Long userId, long total, Map<String, Long> byStatus) {
}
