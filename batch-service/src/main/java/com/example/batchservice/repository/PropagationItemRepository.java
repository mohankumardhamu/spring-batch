package com.example.batchservice.repository;

import com.example.batchservice.entity.ItemStatus;
import com.example.batchservice.entity.PropagationItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PropagationItemRepository extends JpaRepository<PropagationItem, Long> {

    /** Used by the Spring Batch reader (paged, ordered by id). */
    Page<PropagationItem> findByRequestId(Long requestId, Pageable pageable);

    Page<PropagationItem> findByRequestIdAndStatus(Long requestId, ItemStatus status, Pageable pageable);

    long countByRequestIdAndStatus(Long requestId, ItemStatus status);
}
