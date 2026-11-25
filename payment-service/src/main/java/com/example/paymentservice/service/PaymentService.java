package com.example.paymentservice.service;

import com.example.paymentservice.dto.CountResponse;
import com.example.paymentservice.dto.PaymentRequest;
import com.example.paymentservice.dto.PaymentResponse;
import com.example.paymentservice.entity.Payment;
import com.example.paymentservice.entity.PaymentStatus;
import com.example.paymentservice.exception.ResourceNotFoundException;
import com.example.paymentservice.repository.PaymentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class PaymentService {

    private final PaymentRepository paymentRepository;

    public PaymentService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Transactional
    public PaymentResponse create(PaymentRequest request) {
        PaymentStatus status = request.status() != null ? request.status() : PaymentStatus.PENDING;
        Payment payment = new Payment(request.orderId(), request.userId(), request.amount(), request.method(),
                status, newTransactionRef());
        return PaymentResponse.from(paymentRepository.save(payment));
    }

    public PaymentResponse getById(Long id) {
        return PaymentResponse.from(findOrThrow(id));
    }

    public CountResponse count(Long orderId, Long userId) {
        List<Object[]> rows;
        if (orderId != null) {
            rows = paymentRepository.countGroupByStatusForOrder(orderId);
        } else if (userId != null) {
            rows = paymentRepository.countGroupByStatusForUser(userId);
        } else {
            rows = paymentRepository.countGroupByStatus();
        }
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (PaymentStatus s : PaymentStatus.values()) {
            byStatus.put(s.name(), 0L);
        }
        long total = 0;
        BigDecimal amount = BigDecimal.ZERO;
        for (Object[] row : rows) {
            long n = (Long) row[1];
            byStatus.put(((PaymentStatus) row[0]).name(), n);
            total += n;
            amount = amount.add((BigDecimal) row[2]);
        }
        return new CountResponse("payments", orderId == null ? userId : null, orderId, total, byStatus, amount);
    }

    public Page<PaymentResponse> getAll(Long orderId, Long userId, Pageable pageable) {
        Page<Payment> page;
        if (orderId != null) {
            page = paymentRepository.findByOrderId(orderId, pageable);
        } else if (userId != null) {
            page = paymentRepository.findByUserId(userId, pageable);
        } else {
            page = paymentRepository.findAll(pageable);
        }
        return page.map(PaymentResponse::from);
    }

    @Transactional
    public PaymentResponse update(Long id, PaymentRequest request) {
        Payment payment = findOrThrow(id);
        payment.setOrderId(request.orderId());
        payment.setUserId(request.userId());
        payment.setAmount(request.amount());
        payment.setMethod(request.method());
        if (request.status() != null) {
            payment.setStatus(request.status());
        }
        return PaymentResponse.from(paymentRepository.saveAndFlush(payment));
    }

    @Transactional
    public void delete(Long id) {
        paymentRepository.delete(findOrThrow(id));
    }

    private Payment findOrThrow(Long id) {
        return paymentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with id " + id));
    }

    private static String newTransactionRef() {
        return "TXN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
    }
}
