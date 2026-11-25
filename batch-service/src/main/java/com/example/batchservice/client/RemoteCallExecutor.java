package com.example.batchservice.client;

import com.example.batchservice.config.ServicesProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.function.Supplier;

/**
 * Runs a remote call with simple retry: connection failures and 5xx responses are
 * retried with a fixed backoff; 4xx responses fail immediately (they won't succeed on retry).
 */
@Component
public class RemoteCallExecutor {

    private static final Logger log = LoggerFactory.getLogger(RemoteCallExecutor.class);

    private final int maxAttempts;
    private final long backoffMs;

    public RemoteCallExecutor(ServicesProperties props) {
        this.maxAttempts = Math.max(1, props.http().maxAttempts());
        this.backoffMs = props.http().backoff().toMillis();
    }

    public <T> T execute(String description, Supplier<T> call) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return call.get();
            } catch (ResourceAccessException | HttpServerErrorException e) {
                last = e;
                log.warn("{} failed (attempt {}/{}): {}", description, attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    sleep(backoffMs * attempt);
                }
            }
        }
        throw last;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry", e);
        }
    }
}
