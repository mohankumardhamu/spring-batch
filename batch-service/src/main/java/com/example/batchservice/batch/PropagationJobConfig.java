package com.example.batchservice.batch;

import com.example.batchservice.config.PropagationProperties;
import com.example.batchservice.entity.PropagationItem;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.item.support.CompositeItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import java.util.Map;

/**
 * propagateUsersJob      (POST /api/propagate, and every retry)
 * └── propagateStep
 *
 * bulkPropagateUsersJob  (POST /api/propagate/bulk)
 * ├── selectUsersStep    claims the next N not-yet-propagated users from user-service
 * └── propagateStep
 *
 * propagateStep is a chunk-oriented, multi-threaded step:
 *   reader    : JdbcPagingItemReader over the request's items (keyset paging by id, thread-safe)
 *   processor : UserLookup (user-service) → OrderCreation (order-service) → PaymentCreation (payment-service)
 *   writer    : JdbcBatchItemWriter saving each item's results (one batched UPDATE per chunk)
 *   executor  : up to {@code propagation.concurrency} chunks in parallel, each on a virtual thread
 */
@Configuration
public class PropagationJobConfig {

    public static final String JOB_NAME = "propagateUsersJob";
    public static final String BULK_JOB_NAME = "bulkPropagateUsersJob";
    public static final String REQUEST_ID_PARAM = "requestId";
    public static final String ATTEMPT_PARAM = "attempt";

    @Bean
    Job propagateUsersJob(JobRepository jobRepository, Step propagateStep, PropagationJobListener listener) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .listener(listener)
                .start(propagateStep)
                .build();
    }

    @Bean
    Job bulkPropagateUsersJob(JobRepository jobRepository, Step selectUsersStep, Step propagateStep,
                              PropagationJobListener listener) {
        return new JobBuilder(BULK_JOB_NAME, jobRepository)
                .listener(listener)
                .start(selectUsersStep)
                .next(propagateStep)
                .build();
    }

    @Bean
    Step selectUsersStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                         BulkUserSelectionTasklet tasklet) {
        return new StepBuilder("selectUsersStep", jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }

    // throttleLimit is deprecated in Spring Batch 5 (replaced in 6 by the executor's own limit), but in 5.x
    // TaskExecutorRepeatTemplate still caps a multi-threaded step at 4 concurrent chunks unless it is set.
    @SuppressWarnings("removal")
    @Bean
    Step propagateStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                       JdbcPagingItemReader<PropagationItem> propagationItemReader,
                       CompositeItemProcessor<PropagationItem, PropagationItem> propagationProcessor,
                       JdbcBatchItemWriter<PropagationItem> propagationItemWriter,
                       TaskExecutor propagationTaskExecutor,
                       PropagationProperties props) {
        return new StepBuilder("propagateStep", jobRepository)
                .<PropagationItem, PropagationItem>chunk(props.chunkSize(), transactionManager)
                .reader(propagationItemReader)
                .processor(propagationProcessor)
                .writer(propagationItemWriter)
                .taskExecutor(propagationTaskExecutor)
                .throttleLimit(props.concurrency())
                .build();
    }

    /** Virtual threads, at most {@code propagation.concurrency} chunks at a time. */
    @Bean
    TaskExecutor propagationTaskExecutor(PropagationProperties props) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("propagate-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(props.concurrency());
        return executor;
    }

    /**
     * Keyset-paged reader ("WHERE id > :lastId ORDER BY id LIMIT n"), so reading item 9,999,990 is as cheap
     * as item 10. It is thread-safe, as the multi-threaded step needs. saveState=false: a retry re-reads every
     * item, and UserLookupProcessor filters out the ones that already succeeded.
     */
    @Bean
    @StepScope
    JdbcPagingItemReader<PropagationItem> propagationItemReader(
            DataSource dataSource,
            @Value("#{jobParameters['" + REQUEST_ID_PARAM + "']}") Long requestId,
            PropagationProperties props) {
        return new JdbcPagingItemReaderBuilder<PropagationItem>()
                .name("propagationItemReader")
                .dataSource(dataSource)
                .selectClause("SELECT *")
                .fromClause("FROM propagation_item")
                .whereClause("WHERE request_id = :requestId")
                .sortKeys(Map.of("id", Order.ASCENDING))
                .parameterValues(Map.of("requestId", requestId))
                .pageSize(Math.max(props.chunkSize() * 10, 500))
                .rowMapper(new PropagationItemRowMapper())
                .saveState(false)
                .build();
    }

    @Bean
    CompositeItemProcessor<PropagationItem, PropagationItem> propagationProcessor(
            UserLookupProcessor userLookup, OrderCreationProcessor orderCreation,
            PaymentCreationProcessor paymentCreation) {
        return new CompositeItemProcessor<>(List.of(userLookup, orderCreation, paymentCreation));
    }

    @Bean
    JdbcBatchItemWriter<PropagationItem> propagationItemWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<PropagationItem>()
                .dataSource(dataSource)
                .sql("""
                        UPDATE propagation_item SET status = ?, user_id = ?, user_email = ?, order_id = ?,
                               order_amount = ?, payment_id = ?, payment_status = ?, transaction_ref = ?,
                               failed_stage = ?, error_message = ?, processed_at = ?
                        WHERE id = ?""")
                .itemPreparedStatementSetter((item, ps) -> {
                    ps.setString(1, item.getStatus().name());
                    ps.setObject(2, item.getUserId(), Types.BIGINT);
                    ps.setString(3, item.getUserEmail());
                    ps.setObject(4, item.getOrderId(), Types.BIGINT);
                    ps.setBigDecimal(5, item.getOrderAmount());
                    ps.setObject(6, item.getPaymentId(), Types.BIGINT);
                    ps.setString(7, item.getPaymentStatus());
                    ps.setString(8, item.getTransactionRef());
                    ps.setString(9, item.getFailedStage() == null ? null : item.getFailedStage().name());
                    ps.setString(10, item.getErrorMessage());
                    ps.setTimestamp(11, item.getProcessedAt() == null ? null : Timestamp.from(item.getProcessedAt()));
                    ps.setLong(12, item.getId());
                })
                .assertUpdates(true)
                .build();
    }
}
