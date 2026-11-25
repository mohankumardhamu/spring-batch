package com.example.batchservice.batch;

import com.example.batchservice.config.PropagationProperties;
import com.example.batchservice.entity.PropagationItem;
import com.example.batchservice.repository.PropagationItemRepository;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.RepositoryItemWriter;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.batch.item.data.builder.RepositoryItemWriterBuilder;
import org.springframework.batch.item.support.CompositeItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;

/**
 * propagateUsersJob
 * └── propagateStep (chunk-oriented)
 *     reader    : PropagationItems of the request (batchdb)
 *     processor : UserLookup (user-service) → OrderCreation (order-service) → PaymentCreation (payment-service)
 *     writer    : saves each item's results back to batchdb
 */
@Configuration
public class PropagationJobConfig {

    public static final String JOB_NAME = "propagateUsersJob";
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
    Step propagateStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                       RepositoryItemReader<PropagationItem> propagationItemReader,
                       CompositeItemProcessor<PropagationItem, PropagationItem> propagationProcessor,
                       RepositoryItemWriter<PropagationItem> propagationItemWriter,
                       PropagationProperties props) {
        return new StepBuilder("propagateStep", jobRepository)
                .<PropagationItem, PropagationItem>chunk(props.chunkSize(), transactionManager)
                .reader(propagationItemReader)
                .processor(propagationProcessor)
                .writer(propagationItemWriter)
                .build();
    }

    @Bean
    @StepScope
    RepositoryItemReader<PropagationItem> propagationItemReader(
            PropagationItemRepository repository,
            @Value("#{jobParameters['" + REQUEST_ID_PARAM + "']}") Long requestId,
            PropagationProperties props) {
        return new RepositoryItemReaderBuilder<PropagationItem>()
                .name("propagationItemReader")
                .repository(repository)
                .methodName("findByRequestId")
                .arguments(List.of(requestId))
                .sorts(Map.of("id", Sort.Direction.ASC))
                .pageSize(props.chunkSize())
                .saveState(false) // a retry re-reads all items; SUCCESS ones are filtered by UserLookupProcessor
                .build();
    }

    @Bean
    CompositeItemProcessor<PropagationItem, PropagationItem> propagationProcessor(
            UserLookupProcessor userLookup, OrderCreationProcessor orderCreation,
            PaymentCreationProcessor paymentCreation) {
        return new CompositeItemProcessor<>(List.of(userLookup, orderCreation, paymentCreation));
    }

    @Bean
    RepositoryItemWriter<PropagationItem> propagationItemWriter(PropagationItemRepository repository) {
        return new RepositoryItemWriterBuilder<PropagationItem>()
                .repository(repository)
                .methodName("save")
                .build();
    }
}
