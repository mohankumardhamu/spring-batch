package com.example.batchservice.repository;

import com.example.batchservice.entity.ItemStatus;
import com.example.batchservice.entity.PropagationItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PropagationItemRepository extends JpaRepository<PropagationItem, Long> {

    Page<PropagationItem> findByRequestId(Long requestId, Pageable pageable);

    Page<PropagationItem> findByRequestIdAndStatus(Long requestId, ItemStatus status, Pageable pageable);

    long countByRequestIdAndStatus(Long requestId, ItemStatus status);

    /** Rows of [ItemStatus, Long count] for one request (one query instead of one per status). */
    @Query("select i.status, count(i) from PropagationItem i where i.requestId = :requestId group by i.status")
    List<Object[]> countGroupByStatusForRequest(Long requestId);

    /** Rows of [ItemStatus, Long count] across all requests. */
    @Query("select i.status, count(i) from PropagationItem i group by i.status")
    List<Object[]> countGroupByStatus();
}
