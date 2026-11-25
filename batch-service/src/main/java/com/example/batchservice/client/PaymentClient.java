package com.example.batchservice.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

/** HTTP client for payment-service (/api/payments). */
@Component
public class PaymentClient {

    public record CreatePaymentRequest(Long orderId, Long userId, BigDecimal amount, String method, String status) {
    }

    public record PaymentDto(Long id, Long orderId, Long userId, BigDecimal amount, String method,
                             String status, String transactionRef) {
    }

    private final RestClient restClient;
    private final RemoteCallExecutor executor;

    public PaymentClient(@Qualifier("paymentRestClient") RestClient restClient, RemoteCallExecutor executor) {
        this.restClient = restClient;
        this.executor = executor;
    }

    public PaymentDto create(CreatePaymentRequest request) {
        return executor.execute("POST payment for order " + request.orderId(), () -> restClient.post()
                .uri("/api/payments")
                .body(request)
                .retrieve()
                .body(PaymentDto.class));
    }
}
