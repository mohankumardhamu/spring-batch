package com.example.userservice.dto;

import java.util.List;

/**
 * @param chunks        number of chunks the work was split into (one virtual thread each)
 * @param chunkSize     rows inserted per chunk / transaction
 * @param concurrency   max chunks inserting at the same time (= DB connection pool size)
 * @param rowsPerSecond insert throughput
 */
public record GenerateUsersResponse(
        int requested,
        int created,
        int failed,
        long durationMs,
        long rowsPerSecond,
        int chunks,
        int chunkSize,
        int concurrency,
        String threadModel,
        List<String> sampleErrors) {
}
