package com.example.batchservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "propagation")
public record PropagationProperties(int chunkSize, int maxIdentifiersPerRequest,
                                    SampleOrder sampleOrder, Payment payment) {

    public record SampleOrder(String productName, int quantity, BigDecimal unitPrice) {
    }

    public record Payment(String method, String status) {
    }
}
