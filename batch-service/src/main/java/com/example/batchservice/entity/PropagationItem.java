package com.example.batchservice.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One user identifier to propagate, plus the outcome of every hop. This is both the
 * Spring Batch input (reader) and output (writer), so a FAILED item shows exactly
 * which hop failed and why, and a re-run resumes from the hop that failed.
 */
@Entity
@Table(name = "propagation_item", indexes = @Index(name = "idx_item_request_id", columnList = "requestId"))
public class PropagationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long requestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private IdentifierType identifierType;

    @Column(nullable = false, length = 150)
    private String identifier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ItemStatus status = ItemStatus.PENDING;

    // results of each hop
    private Long userId;
    @Column(length = 150)
    private String userEmail;
    private Long orderId;
    @Column(precision = 14, scale = 2)
    private BigDecimal orderAmount;
    private Long paymentId;
    @Column(length = 20)
    private String paymentStatus;
    @Column(length = 40)
    private String transactionRef;

    // failure details
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Stage failedStage;
    @Column(length = 1000)
    private String errorMessage;

    private Instant processedAt;

    protected PropagationItem() {
    }

    public PropagationItem(Long requestId, IdentifierType identifierType, String identifier) {
        this.requestId = requestId;
        this.identifierType = identifierType;
        this.identifier = identifier;
    }

    public void markFailed(Stage stage, String message) {
        this.status = ItemStatus.FAILED;
        this.failedStage = stage;
        this.errorMessage = message == null ? null : message.substring(0, Math.min(1000, message.length()));
        this.processedAt = Instant.now();
    }

    public void markSuccess() {
        this.status = ItemStatus.SUCCESS;
        this.failedStage = null;
        this.errorMessage = null;
        this.processedAt = Instant.now();
    }

    /** Clears a previous failure so the item is retried from the hop that failed. */
    public void resetForRetry() {
        this.status = ItemStatus.PENDING;
        this.failedStage = null;
        this.errorMessage = null;
    }

    public boolean isSuccess() { return status == ItemStatus.SUCCESS; }

    public boolean isFailed() { return status == ItemStatus.FAILED; }

    public Long getId() { return id; }
    public Long getRequestId() { return requestId; }
    public IdentifierType getIdentifierType() { return identifierType; }
    public String getIdentifier() { return identifier; }
    public ItemStatus getStatus() { return status; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUserEmail() { return userEmail; }
    public void setUserEmail(String userEmail) { this.userEmail = userEmail; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public BigDecimal getOrderAmount() { return orderAmount; }
    public void setOrderAmount(BigDecimal orderAmount) { this.orderAmount = orderAmount; }
    public Long getPaymentId() { return paymentId; }
    public void setPaymentId(Long paymentId) { this.paymentId = paymentId; }
    public String getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(String paymentStatus) { this.paymentStatus = paymentStatus; }
    public String getTransactionRef() { return transactionRef; }
    public void setTransactionRef(String transactionRef) { this.transactionRef = transactionRef; }
    public Stage getFailedStage() { return failedStage; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getProcessedAt() { return processedAt; }
}
