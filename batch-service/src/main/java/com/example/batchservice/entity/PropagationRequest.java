package com.example.batchservice.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/** One call to POST /api/propagate. Each request is processed by exactly one Spring Batch job execution. */
@Entity
@Table(name = "propagation_request")
public class PropagationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RequestStatus status = RequestStatus.SUBMITTED;

    private Long jobExecutionId;

    @Column(nullable = false)
    private int totalItems;

    /** 1 for the first run, incremented by every retry (also a Spring Batch identifying job parameter). */
    @Column(nullable = false, columnDefinition = "integer default 1")
    private int attempts = 1;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    protected PropagationRequest() {
    }

    public PropagationRequest(int totalItems) {
        this.totalItems = totalItems;
    }

    public Long getId() { return id; }
    public RequestStatus getStatus() { return status; }
    public Long getJobExecutionId() { return jobExecutionId; }
    public int getTotalItems() { return totalItems; }
    public int getAttempts() { return attempts; }

    /** Prepares a finished request to be run again. */
    public void resubmit() {
        this.attempts++;
        this.status = RequestStatus.SUBMITTED;
    }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
