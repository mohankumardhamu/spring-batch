package com.example.batchservice.repository;

import com.example.batchservice.entity.PropagationRequest;
import com.example.batchservice.entity.RequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface PropagationRequestRepository extends JpaRepository<PropagationRequest, Long> {

    /** Rows of [RequestStatus, Long count]. */
    @Query("select r.status, count(r) from PropagationRequest r group by r.status")
    java.util.List<Object[]> countGroupByStatus();

    @Transactional
    @Modifying
    @Query("update PropagationRequest r set r.status = :status, r.jobExecutionId = :jobExecutionId, "
            + "r.updatedAt = CURRENT_TIMESTAMP where r.id = :id")
    int updateStatus(Long id, RequestStatus status, Long jobExecutionId);

    @Transactional
    @Modifying
    @Query("update PropagationRequest r set r.status = :to, r.updatedAt = CURRENT_TIMESTAMP where r.status in :from")
    int bulkUpdateStatus(java.util.Collection<RequestStatus> from, RequestStatus to);
}
