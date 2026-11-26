package com.example.batchservice.batch;

import com.example.batchservice.entity.PropagationItem;
import com.example.batchservice.entity.RequestType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Business metrics for propagation (Spring Batch's own spring_batch_* metrics cover jobs, steps and chunks):
 * <ul>
 *   <li>{@code propagation_users_total{result="success|failed", stage=...}}: users processed, by outcome
 *       and the hop that failed</li>
 *   <li>{@code propagation_requests_total{type="MANUAL|BULK"}}: requests submitted</li>
 *   <li>{@code propagation_bulk_selected_users_total}: users claimed by bulk selection</li>
 * </ul>
 */
@Component
public class PropagationMetrics {

    private final MeterRegistry registry;
    private final Counter bulkSelected;

    public PropagationMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.bulkSelected = Counter.builder("propagation.bulk.selected.users")
                .description("Users selected by bulk propagation").register(registry);
    }

    public void itemProcessed(PropagationItem item) {
        String stage = item.getFailedStage() == null ? "none" : item.getFailedStage().name();
        Counter.builder("propagation.users").description("Users processed by the propagate step")
                .tag("result", item.isFailed() ? "failed" : "success").tag("stage", stage)
                .register(registry).increment();
    }

    public void requestSubmitted(RequestType type) {
        Counter.builder("propagation.requests").description("Propagation requests submitted")
                .tag("type", type.name()).register(registry).increment();
    }

    public void bulkSelected(int users) {
        bulkSelected.increment(users);
    }
}
