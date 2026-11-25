package com.example.batchservice.batch;

import com.example.batchservice.entity.ItemStatus;
import com.example.batchservice.entity.RequestStatus;
import com.example.batchservice.repository.PropagationItemRepository;
import com.example.batchservice.repository.PropagationRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.stereotype.Component;

/** Keeps PropagationRequest.status in sync with the Spring Batch job execution. */
@Component
public class PropagationJobListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(PropagationJobListener.class);

    private final PropagationRequestRepository requestRepository;
    private final PropagationItemRepository itemRepository;

    public PropagationJobListener(PropagationRequestRepository requestRepository,
                                  PropagationItemRepository itemRepository) {
        this.requestRepository = requestRepository;
        this.itemRepository = itemRepository;
    }

    @Override
    public void beforeJob(JobExecution jobExecution) {
        Long requestId = requestId(jobExecution);
        requestRepository.updateStatus(requestId, RequestStatus.RUNNING, jobExecution.getId());
        log.info("Propagation request {} started (job execution {})", requestId, jobExecution.getId());
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        Long requestId = requestId(jobExecution);
        RequestStatus status;
        if (jobExecution.getStatus() != BatchStatus.COMPLETED) {
            status = RequestStatus.FAILED;
        } else {
            long failed = itemRepository.countByRequestIdAndStatus(requestId, ItemStatus.FAILED);
            long pending = itemRepository.countByRequestIdAndStatus(requestId, ItemStatus.PENDING);
            status = (failed + pending) == 0 ? RequestStatus.COMPLETED : RequestStatus.COMPLETED_WITH_ERRORS;
        }
        requestRepository.updateStatus(requestId, status, jobExecution.getId());
        log.info("Propagation request {} finished: {} (job status {})", requestId, status, jobExecution.getStatus());
    }

    private static Long requestId(JobExecution jobExecution) {
        return jobExecution.getJobParameters().getLong(PropagationJobConfig.REQUEST_ID_PARAM);
    }
}
