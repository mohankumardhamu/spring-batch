package com.example.orderservice.service;

import com.example.orderservice.dto.CountResponse;
import com.example.orderservice.dto.OrderRequest;
import com.example.orderservice.dto.OrderResponse;
import com.example.orderservice.entity.Order;
import com.example.orderservice.entity.OrderStatus;
import com.example.orderservice.exception.ResourceNotFoundException;
import com.example.orderservice.repository.OrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional
    public OrderResponse create(OrderRequest request) {
        OrderStatus status = request.status() != null ? request.status() : OrderStatus.CREATED;
        Order order = new Order(request.userId(), request.productName(), request.quantity(), request.unitPrice(), status);
        return OrderResponse.from(orderRepository.save(order));
    }

    public OrderResponse getById(Long id) {
        return OrderResponse.from(findOrThrow(id));
    }

    public CountResponse count(Long userId) {
        List<Object[]> rows = userId == null
                ? orderRepository.countGroupByStatus()
                : orderRepository.countGroupByStatusForUser(userId);
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (OrderStatus s : OrderStatus.values()) {
            byStatus.put(s.name(), 0L);
        }
        long total = 0;
        for (Object[] row : rows) {
            long n = (Long) row[1];
            byStatus.put(((OrderStatus) row[0]).name(), n);
            total += n;
        }
        return new CountResponse("orders", userId, total, byStatus);
    }

    public Page<OrderResponse> getAll(Long userId, Pageable pageable) {
        Page<Order> page = userId == null
                ? orderRepository.findAll(pageable)
                : orderRepository.findByUserId(userId, pageable);
        return page.map(OrderResponse::from);
    }

    @Transactional
    public OrderResponse update(Long id, OrderRequest request) {
        Order order = findOrThrow(id);
        order.setUserId(request.userId());
        order.setProductName(request.productName());
        order.setPricing(request.quantity(), request.unitPrice());
        if (request.status() != null) {
            order.setStatus(request.status());
        }
        return OrderResponse.from(orderRepository.saveAndFlush(order));
    }

    @Transactional
    public void delete(Long id) {
        orderRepository.delete(findOrThrow(id));
    }

    private Order findOrThrow(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id " + id));
    }
}
