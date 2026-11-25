package com.example.batchservice.batch;

import com.example.batchservice.client.OrderClient;
import com.example.batchservice.config.PropagationProperties;
import com.example.batchservice.entity.PropagationItem;
import com.example.batchservice.entity.Stage;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

/** Hop 2: create a default/sample order for the user in order-service. */
@Component
public class OrderCreationProcessor implements ItemProcessor<PropagationItem, PropagationItem> {

    private final OrderClient orderClient;
    private final PropagationProperties.SampleOrder sampleOrder;

    public OrderCreationProcessor(OrderClient orderClient, PropagationProperties props) {
        this.orderClient = orderClient;
        this.sampleOrder = props.sampleOrder();
    }

    @Override
    public PropagationItem process(PropagationItem item) {
        if (item.isFailed() || item.getOrderId() != null) {
            return item;
        }
        try {
            OrderClient.OrderDto order = orderClient.create(new OrderClient.CreateOrderRequest(
                    item.getUserId(), sampleOrder.productName(), sampleOrder.quantity(), sampleOrder.unitPrice()));
            item.setOrderId(order.id());
            item.setOrderAmount(order.totalAmount());
        } catch (Exception e) {
            item.markFailed(Stage.ORDER_CREATION, RemoteErrors.describe("order-service", e));
        }
        return item;
    }
}
