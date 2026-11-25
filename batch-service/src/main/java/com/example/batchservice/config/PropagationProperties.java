package com.example.batchservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * @param chunkSize   items per chunk (one transaction, one thread)
 * @param concurrency chunks processed in parallel by the propagate step (virtual threads)
 */
@ConfigurationProperties(prefix = "propagation")
public record PropagationProperties(int chunkSize, int concurrency, int maxIdentifiersPerRequest,
                                    Bulk bulk, SampleOrder sampleOrder, Payment payment) {

    /**
     * @param maxCount         max users per bulk request
     * @param selectionPageSize user ids fetched from user-service per call while selecting
     */
    public record Bulk(int maxCount, int selectionPageSize) {
    }

    public record SampleOrder(String productName, int quantity, BigDecimal unitPrice) {
    }

    public record Payment(String method, String status) {
    }
}
