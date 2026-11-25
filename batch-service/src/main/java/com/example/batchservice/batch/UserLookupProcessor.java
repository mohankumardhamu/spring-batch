package com.example.batchservice.batch;

import com.example.batchservice.client.UserClient;
import com.example.batchservice.entity.IdentifierType;
import com.example.batchservice.entity.PropagationItem;
import com.example.batchservice.entity.Stage;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * Hop 1: resolve the user id/email against user-service.
 * Being first in the chain, it also filters out items that already succeeded (retries)
 * and clears previous failures so the remaining hops run again.
 */
@Component
public class UserLookupProcessor implements ItemProcessor<PropagationItem, PropagationItem> {

    private final UserClient userClient;

    public UserLookupProcessor(UserClient userClient) {
        this.userClient = userClient;
    }

    @Override
    public PropagationItem process(PropagationItem item) {
        if (item.isSuccess()) {
            return null; // nothing to do; returning null filters the item out of the chunk
        }
        if (item.isFailed()) {
            item.resetForRetry();
        }
        if (item.getUserId() != null) {
            return item; // already resolved in a previous run
        }
        try {
            UserClient.UserDto user = item.getIdentifierType() == IdentifierType.USER_ID
                    ? userClient.getById(Long.valueOf(item.getIdentifier()))
                    : userClient.getByEmail(item.getIdentifier());
            item.setUserId(user.id());
            item.setUserEmail(user.email());
        } catch (Exception e) {
            item.markFailed(Stage.USER_LOOKUP, RemoteErrors.describe("user-service", e));
        }
        return item;
    }
}
