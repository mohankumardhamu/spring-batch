package com.example.batchservice.batch;

import com.example.batchservice.client.PaymentClient;
import com.example.batchservice.config.PropagationProperties;
import com.example.batchservice.entity.PropagationItem;
import com.example.batchservice.entity.Stage;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

/** Hop 3: create the matching payment for the order in payment-service. */
@Component
public class PaymentCreationProcessor implements ItemProcessor<PropagationItem, PropagationItem> {

    private final PaymentClient paymentClient;
    private final PropagationProperties.Payment payment;
    private final PropagationMetrics metrics;

    public PaymentCreationProcessor(PaymentClient paymentClient, PropagationProperties props,
                                    PropagationMetrics metrics) {
        this.paymentClient = paymentClient;
        this.payment = props.payment();
        this.metrics = metrics;
    }

    @Override
    public PropagationItem process(PropagationItem item) {
        if (item.isFailed()) {
            metrics.itemProcessed(item); // failed in an earlier hop; last processor in the chain records it
            return item;
        }
        if (item.getPaymentId() == null) {
            try {
                PaymentClient.PaymentDto created = paymentClient.create(new PaymentClient.CreatePaymentRequest(
                        item.getOrderId(), item.getUserId(), item.getOrderAmount(), payment.method(), payment.status()));
                item.setPaymentId(created.id());
                item.setPaymentStatus(created.status());
                item.setTransactionRef(created.transactionRef());
            } catch (Exception e) {
                item.markFailed(Stage.PAYMENT_CREATION, RemoteErrors.describe("payment-service", e));
                metrics.itemProcessed(item);
                return item;
            }
        }
        item.markSuccess();
        metrics.itemProcessed(item);
        return item;
    }
}
