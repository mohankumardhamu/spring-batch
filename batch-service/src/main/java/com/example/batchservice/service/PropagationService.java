package com.example.batchservice.service;

import com.example.batchservice.batch.PropagationJobConfig;
import com.example.batchservice.batch.PropagationMetrics;
import com.example.batchservice.config.PropagationProperties;
import com.example.batchservice.dto.BulkPropagateRequest;
import com.example.batchservice.dto.CountResponse;
import com.example.batchservice.dto.PropagateRequest;
import com.example.batchservice.dto.PropagationItemResponse;
import com.example.batchservice.dto.PropagationStatusResponse;
import com.example.batchservice.entity.*;
import com.example.batchservice.exception.ConflictException;
import com.example.batchservice.exception.ResourceNotFoundException;
import com.example.batchservice.repository.PropagationItemRepository;
import com.example.batchservice.repository.PropagationRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;

@Service
public class PropagationService {

    private static final Logger log = LoggerFactory.getLogger(PropagationService.class);

    private final PropagationRequestRepository requestRepository;
    private final PropagationItemRepository itemRepository;
    private final TransactionTemplate transactionTemplate;
    private final JobExplorer jobExplorer;
    private final Job propagateUsersJob;
    private final Job bulkPropagateUsersJob;
    private final TaskExecutorJobLauncher asyncJobLauncher;
    private final int maxIdentifiers;
    private final int maxBulkCount;
    private final PropagationMetrics metrics;

    public PropagationService(PropagationRequestRepository requestRepository,
                              PropagationItemRepository itemRepository,
                              TransactionTemplate transactionTemplate,
                              JobRepository jobRepository,
                              JobExplorer jobExplorer,
                              Job propagateUsersJob,
                              Job bulkPropagateUsersJob,
                              PropagationProperties props,
                              PropagationMetrics metrics) throws Exception {
        this.metrics = metrics;
        this.requestRepository = requestRepository;
        this.itemRepository = itemRepository;
        this.transactionTemplate = transactionTemplate;
        this.jobExplorer = jobExplorer;
        this.propagateUsersJob = propagateUsersJob;
        this.bulkPropagateUsersJob = bulkPropagateUsersJob;
        this.maxIdentifiers = props.maxIdentifiersPerRequest();
        this.maxBulkCount = props.bulk().maxCount();

        // Async launcher: run() returns immediately with a STARTING execution; the job runs on a virtual thread.
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("propagation-job-");
        executor.setVirtualThreads(true);
        this.asyncJobLauncher = new TaskExecutorJobLauncher();
        this.asyncJobLauncher.setJobRepository(jobRepository);
        this.asyncJobLauncher.setTaskExecutor(executor);
        this.asyncJobLauncher.afterPropertiesSet();
    }

    /** Result of a submission: the stored request plus the job execution that will process it. */
    public record Submission(PropagationRequest request, Long jobExecutionId) {
    }

    public Submission submit(PropagateRequest request) {
        List<PropagationItem> items = toItems(request);

        // 1. persist the request + its items (committed before the job starts reading them)
        PropagationRequest saved = transactionTemplate.execute(tx -> {
            PropagationRequest r = requestRepository.save(new PropagationRequest(items.size()));
            itemRepository.saveAll(items.stream()
                    .map(i -> new PropagationItem(r.getId(), i.getIdentifierType(), i.getIdentifier()))
                    .toList());
            return r;
        });

        // 2. launch the Spring Batch job asynchronously
        Submission submission = launch(propagateUsersJob, saved);
        metrics.requestSubmitted(RequestType.MANUAL);
        log.info("Submitted propagation request {} with {} items as job execution {}",
                saved.getId(), items.size(), submission.jobExecutionId());
        return submission;
    }

    /**
     * Propagates the next {@code count} users that have not been propagated yet. The users are selected
     * by the job itself (selectUsersStep), so this returns immediately even for millions of users.
     */
    public Submission submitBulk(BulkPropagateRequest request) {
        Integer count = request.count();
        if (count == null || count < 1 || count > maxBulkCount) {
            throw new IllegalArgumentException("count must be between 1 and " + maxBulkCount);
        }
        PropagationRequest saved = requestRepository.save(PropagationRequest.bulk(count));
        Submission submission = launch(bulkPropagateUsersJob, saved);
        metrics.requestSubmitted(RequestType.BULK);
        log.info("Submitted bulk propagation request {} for {} users as job execution {}",
                saved.getId(), count, submission.jobExecutionId());
        return submission;
    }

    /**
     * Re-runs a finished request: SUCCESS items are skipped, FAILED/PENDING items resume from the hop
     * that failed (an order that was already created is not created twice).
     */
    public Submission retry(Long requestId) {
        PropagationRequest request = transactionTemplate.execute(tx -> {
            PropagationRequest r = findOrThrow(requestId);
            if (r.getStatus() == RequestStatus.SUBMITTED || r.getStatus() == RequestStatus.RUNNING) {
                throw new ConflictException("Propagation request " + requestId + " is still " + r.getStatus());
            }
            if (r.getStatus() == RequestStatus.COMPLETED) {
                throw new ConflictException("Propagation request " + requestId + " already completed successfully");
            }
            r.resubmit();
            return requestRepository.save(r);
        });
        // A bulk request whose selection step never committed has no items yet: select again.
        Job job = request.getType() == RequestType.BULK && request.getTotalItems() == 0
                ? bulkPropagateUsersJob : propagateUsersJob;
        Submission submission = launch(job, request);
        log.info("Retrying propagation request {} (attempt {}) as job execution {}",
                requestId, request.getAttempts(), submission.jobExecutionId());
        return submission;
    }

    private Submission launch(Job job, PropagationRequest request) {
        JobParameters params = new JobParametersBuilder()
                .addLong(PropagationJobConfig.REQUEST_ID_PARAM, request.getId())
                .addLong(PropagationJobConfig.ATTEMPT_PARAM, (long) request.getAttempts())
                .toJobParameters();
        try {
            JobExecution execution = asyncJobLauncher.run(job, params);
            return new Submission(request, execution.getId());
        } catch (Exception e) {
            requestRepository.updateStatus(request.getId(), RequestStatus.FAILED, request.getJobExecutionId());
            throw new IllegalStateException("Failed to launch propagation job: " + e.getMessage(), e);
        }
    }

    public PropagationStatusResponse getStatus(Long requestId) {
        PropagationRequest r = findOrThrow(requestId);
        JobExecution job = r.getJobExecutionId() == null ? null : jobExplorer.getJobExecution(r.getJobExecutionId());
        Map<String, Long> items = tally(ItemStatus.values(), itemRepository.countGroupByStatusForRequest(requestId));
        Instant start = job == null || job.getStartTime() == null ? null
                : job.getStartTime().atZone(ZoneId.systemDefault()).toInstant();
        Instant end = job == null || job.getEndTime() == null ? null
                : job.getEndTime().atZone(ZoneId.systemDefault()).toInstant();
        Long durationMs = start == null ? null
                : Math.max(1, Duration.between(start, end != null ? end : Instant.now()).toMillis());
        long processed = items.get("SUCCESS") + items.get("FAILED");
        return new PropagationStatusResponse(
                r.getId(), r.getType(), r.getStatus(), r.getJobExecutionId(),
                job == null ? null : job.getJobInstance().getJobName(),
                job == null ? null : job.getStatus().name(),
                job == null ? null : job.getExitStatus().getExitCode(),
                start, end,
                r.getRequestedCount(),
                r.getTotalItems(),
                r.getAttempts(),
                items.get("SUCCESS"), items.get("FAILED"), items.get("PENDING"),
                durationMs,
                durationMs == null ? null : processed * 1000 / durationMs,
                r.getCreatedAt(), r.getUpdatedAt());
    }

    public Page<PropagationItemResponse> getItems(Long requestId, ItemStatus status, Pageable pageable) {
        findOrThrow(requestId);
        Page<PropagationItem> page = status == null
                ? itemRepository.findByRequestId(requestId, pageable)
                : itemRepository.findByRequestIdAndStatus(requestId, status, pageable);
        return page.map(PropagationItemResponse::from);
    }

    public CountResponse count() {
        Map<String, Long> requests = tally(RequestStatus.values(), requestRepository.countGroupByStatus());
        Map<String, Long> items = tally(ItemStatus.values(), itemRepository.countGroupByStatus());
        return new CountResponse("propagationRequests", sum(requests), requests, sum(items), items);
    }

    private static Map<String, Long> tally(Enum<?>[] all, List<Object[]> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Enum<?> e : all) {
            counts.put(e.name(), 0L);
        }
        for (Object[] row : rows) {
            counts.put(((Enum<?>) row[0]).name(), (Long) row[1]);
        }
        return counts;
    }

    private static long sum(Map<String, Long> counts) {
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }

    public Page<PropagationRequest> getRequests(Pageable pageable) {
        return requestRepository.findAll(pageable);
    }

    private PropagationRequest findOrThrow(Long requestId) {
        return requestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Propagation request not found with id " + requestId));
    }

    /** Validates and de-duplicates the identifiers (order preserved). */
    private List<PropagationItem> toItems(PropagateRequest request) {
        Set<Long> ids = new LinkedHashSet<>();
        if (request.userIds() != null) {
            for (Long id : request.userIds()) {
                if (id == null || id <= 0) {
                    throw new IllegalArgumentException("userIds must contain positive numbers, got: " + id);
                }
                ids.add(id);
            }
        }
        Set<String> emails = new LinkedHashSet<>();
        if (request.emails() != null) {
            for (String email : request.emails()) {
                if (email == null || !email.contains("@") || email.isBlank()) {
                    throw new IllegalArgumentException("emails must contain valid email addresses, got: " + email);
                }
                emails.add(email.trim().toLowerCase());
            }
        }
        int total = ids.size() + emails.size();
        if (total == 0) {
            throw new IllegalArgumentException("Provide at least one entry in 'userIds' or 'emails'");
        }
        if (total > maxIdentifiers) {
            throw new IllegalArgumentException("At most " + maxIdentifiers + " identifiers per request, got " + total);
        }
        List<PropagationItem> items = new ArrayList<>(total);
        ids.forEach(id -> items.add(new PropagationItem(null, IdentifierType.USER_ID, id.toString())));
        emails.forEach(e -> items.add(new PropagationItem(null, IdentifierType.EMAIL, e)));
        return items;
    }
}
