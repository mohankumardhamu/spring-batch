package com.example.batchservice.dto;

import java.util.Map;

/**
 * @param byStatus  propagation requests per status (every status, including 0 counts)
 * @param itemTotal total user identifiers across all requests
 * @param itemsByStatus those identifiers per status (SUCCESS / FAILED / PENDING)
 */
public record CountResponse(String resource, long total, Map<String, Long> byStatus,
                            long itemTotal, Map<String, Long> itemsByStatus) {
}
