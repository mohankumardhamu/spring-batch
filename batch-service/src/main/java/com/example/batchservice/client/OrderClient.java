package com.example.batchservice.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

/** HTTP client for order-service (/api/orders). */
@Component
public class OrderClient {

    public record CreateOrderRequest(Long userId, String productName, Integer quantity, BigDecimal unitPrice) {
    }

    public record OrderDto(Long id, Long userId, String productName, Integer quantity, BigDecimal unitPrice,
                           BigDecimal totalAmount, String status) {
    }

    private final RestClient restClient;
    private final RemoteCallExecutor executor;

    public OrderClient(@Qualifier("orderRestClient") RestClient restClient, RemoteCallExecutor executor) {
        this.restClient = restClient;
        this.executor = executor;
    }

    public OrderDto create(CreateOrderRequest request) {
        return executor.execute("POST order for user " + request.userId(), () -> restClient.post()
                .uri("/api/orders")
                .body(request)
                .retrieve()
                .body(OrderDto.class));
    }
}
