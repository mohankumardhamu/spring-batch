package com.example.batchservice.batch;

import com.example.batchservice.entity.RequestStatus;
import com.example.batchservice.repository.PropagationRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * If the service stops while a job is running, Spring Batch leaves that execution as STARTED forever
 * and the request would show RUNNING forever. On startup we mark such executions (and their requests)
 * FAILED so clients see the truth and can call POST /api/propagate/{id}/retry.
 * <p>
 * Assumes a single batch-service instance (the default docker-compose setup).
 */
@Component
public class OrphanedJobRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OrphanedJobRecovery.class);

    private final JobExplorer jobExplorer;
    private final JobRepository jobRepository;
    private final PropagationRequestRepository requestRepository;

    public OrphanedJobRecovery(JobExplorer jobExplorer, JobRepository jobRepository,
                               PropagationRequestRepository requestRepository) {
        this.jobExplorer = jobExplorer;
        this.jobRepository = jobRepository;
        this.requestRepository = requestRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        Set<JobExecution> orphans = jobExplorer.findRunningJobExecutions(PropagationJobConfig.JOB_NAME);
        LocalDateTime now = LocalDateTime.now();
        ExitStatus abandoned = ExitStatus.FAILED.addExitDescription("Interrupted by batch-service shutdown");
        for (JobExecution execution : orphans) {
            for (StepExecution step : execution.getStepExecutions()) {
                if (step.getStatus().isRunning()) {
                    step.setStatus(BatchStatus.FAILED);
                    step.setExitStatus(abandoned);
                    step.setEndTime(now);
                    jobRepository.update(step);
                }
            }
            execution.setStatus(BatchStatus.FAILED);
            execution.setExitStatus(abandoned);
            execution.setEndTime(now);
            jobRepository.update(execution);
            log.warn("Marked orphaned job execution {} ({}) as FAILED", execution.getId(),
                    execution.getJobParameters());
        }
        int requests = requestRepository.bulkUpdateStatus(
                List.of(RequestStatus.SUBMITTED, RequestStatus.RUNNING), RequestStatus.FAILED);
        if (requests > 0) {
            log.warn("Marked {} interrupted propagation request(s) as FAILED; use POST /api/propagate/<id>/retry",
                    requests);
        }
    }
}
