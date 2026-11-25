package com.example.paymentservice.repository;

import com.example.paymentservice.entity.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Page<Payment> findByOrderId(Long orderId, Pageable pageable);

    Page<Payment> findByUserId(Long userId, Pageable pageable);
}
