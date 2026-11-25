package com.example.paymentservice.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payments",
        indexes = {
                @Index(name = "idx_payments_order_id", columnList = "orderId"),
                @Index(name = "idx_payments_user_id", columnList = "userId")
        },
        uniqueConstraints = @UniqueConstraint(name = "uk_payments_txn_ref", columnNames = "transactionRef"))
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Id of the order in order-service (no cross-database FK). */
    @Column(nullable = false)
    private Long orderId;

    /** Id of the user in user-service (no cross-database FK). */
    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(nullable = false, length = 40)
    private String transactionRef;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    protected Payment() {
    }

    public Payment(Long orderId, Long userId, BigDecimal amount, PaymentMethod method,
                   PaymentStatus status, String transactionRef) {
        this.orderId = orderId;
        this.userId = userId;
        this.amount = amount;
        this.method = method;
        this.status = status;
        this.transactionRef = transactionRef;
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public PaymentMethod getMethod() { return method; }
    public void setMethod(PaymentMethod method) { this.method = method; }
    public PaymentStatus getStatus() { return status; }
    public void setStatus(PaymentStatus status) { this.status = status; }
    public String getTransactionRef() { return transactionRef; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
