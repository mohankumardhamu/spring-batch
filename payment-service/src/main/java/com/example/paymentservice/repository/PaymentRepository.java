package com.example.paymentservice.repository;

import com.example.paymentservice.entity.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Page<Payment> findByOrderId(Long orderId, Pageable pageable);

    Page<Payment> findByUserId(Long userId, Pageable pageable);

    /** Rows of [PaymentStatus, Long count, BigDecimal sum(amount)]. */
    @Query("select p.status, count(p), sum(p.amount) from Payment p group by p.status")
    List<Object[]> countGroupByStatus();

    @Query("select p.status, count(p), sum(p.amount) from Payment p where p.userId = :userId group by p.status")
    List<Object[]> countGroupByStatusForUser(Long userId);

    @Query("select p.status, count(p), sum(p.amount) from Payment p where p.orderId = :orderId group by p.status")
    List<Object[]> countGroupByStatusForOrder(Long orderId);
}
