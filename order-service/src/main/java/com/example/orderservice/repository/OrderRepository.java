package com.example.orderservice.repository;

import com.example.orderservice.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Page<Order> findByUserId(Long userId, Pageable pageable);

    /** Rows of [OrderStatus, Long count]. */
    @Query("select o.status, count(o) from Order o group by o.status")
    List<Object[]> countGroupByStatus();

    @Query("select o.status, count(o) from Order o where o.userId = :userId group by o.status")
    List<Object[]> countGroupByStatusForUser(Long userId);
}
