package com.example.batchservice.controller;

import com.example.batchservice.dto.BulkPropagateRequest;
import com.example.batchservice.dto.CountResponse;
import com.example.batchservice.dto.PropagateRequest;
import com.example.batchservice.dto.PropagateSubmitResponse;
import com.example.batchservice.dto.PropagationItemResponse;
import com.example.batchservice.dto.PropagationStatusResponse;
import com.example.batchservice.entity.ItemStatus;
import com.example.batchservice.entity.PropagationRequest;
import com.example.batchservice.service.PropagationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/**
 * Entry point of the propagation flow:
 * POST user ids/emails → Spring Batch job → user-service → order-service → payment-service.
 * The job runs asynchronously; poll the status URL until status is no longer SUBMITTED/RUNNING.
 */
@RestController
@RequestMapping("/api/propagate")
public class PropagationController {

    private final PropagationService propagationService;

    public PropagationController(PropagationService propagationService) {
        this.propagationService = propagationService;
    }

    @PostMapping
    public ResponseEntity<PropagateSubmitResponse> submit(@RequestBody PropagateRequest request) {
        PropagationService.Submission submission = propagationService.submit(request);
        URI statusUri = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(submission.request().getId()).toUri();
        return accepted(submission, statusUri);
    }

    /**
     * Selects the next {@code count} users that have not been propagated yet (from user-service) and
     * propagates them: order + payment for each. Async: returns 202 with a requestId to poll.
     */
    @PostMapping("/bulk")
    public ResponseEntity<PropagateSubmitResponse> submitBulk(@RequestBody BulkPropagateRequest request) {
        PropagationService.Submission submission = propagationService.submitBulk(request);
        return accepted(submission, ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/propagate/{id}").buildAndExpand(submission.request().getId()).toUri());
    }

    private static ResponseEntity<PropagateSubmitResponse> accepted(PropagationService.Submission submission,
                                                                    URI statusUri) {
        PropagationRequest r = submission.request();
        return ResponseEntity.accepted()
                .location(statusUri)
                .body(new PropagateSubmitResponse(r.getId(), r.getType(), submission.jobExecutionId(), r.getStatus(),
                        r.getTotalItems(), r.getRequestedCount(), statusUri.toString(), statusUri + "/items"));
    }

    /** Re-run the FAILED/PENDING items of a finished request (status FAILED or COMPLETED_WITH_ERRORS). */
    @PostMapping("/{requestId}/retry")
    public ResponseEntity<PropagateSubmitResponse> retry(@PathVariable Long requestId) {
        PropagationService.Submission submission = propagationService.retry(requestId);
        return accepted(submission, ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/propagate/{id}").buildAndExpand(requestId).toUri());
    }

    /** Number of propagation requests and user items, per status. */
    @GetMapping("/count")
    public CountResponse count() {
        return propagationService.count();
    }

    @GetMapping("/{requestId}")
    public PropagationStatusResponse status(@PathVariable Long requestId) {
        return propagationService.getStatus(requestId);
    }

    /** Per-user results; filter with ?status=SUCCESS|FAILED|PENDING */
    @GetMapping("/{requestId}/items")
    public Page<PropagationItemResponse> items(@PathVariable Long requestId,
                                               @RequestParam(required = false) ItemStatus status,
                                               @PageableDefault(size = 50, sort = "id") Pageable pageable) {
        return propagationService.getItems(requestId, status, pageable);
    }

    @GetMapping
    public Page<PropagationRequest> list(@PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC)
                                         Pageable pageable) {
        return propagationService.getRequests(pageable);
    }
}
