package com.example.batchservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "services")
public record ServicesProperties(Endpoint user, Endpoint order, Endpoint payment, Http http) {

    public record Endpoint(String url) {
    }

    public record Http(Duration connectTimeout, Duration readTimeout, int maxAttempts, Duration backoff) {
    }
}
