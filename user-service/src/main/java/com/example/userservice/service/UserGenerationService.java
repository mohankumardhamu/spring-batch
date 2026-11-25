package com.example.userservice.service;

import com.example.userservice.dto.GenerateUsersResponse;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates N users concurrently on virtual threads.
 * <p>
 * The work is split into chunks of {@value #CHUNK_SIZE} users. Each chunk runs on its own virtual thread
 * and is inserted in one transaction with JDBC batch inserts (the driver rewrites them into multi-row
 * INSERTs because of {@code reWriteBatchedInserts=true}). That is far faster than saving entities one by
 * one, because JPA cannot batch inserts for IDENTITY ids.
 * <p>
 * A semaphore sized to the connection pool limits how many chunks run at once, so rows are only built
 * in memory once a chunk is about to be inserted. Memory stays flat even for 10 million users.
 */
@Service
public class UserGenerationService {

    private static final Logger log = LoggerFactory.getLogger(UserGenerationService.class);
    static final int CHUNK_SIZE = 10_000;
    private static final int JDBC_BATCH_SIZE = 1_000;
    private static final int MAX_SAMPLE_ERRORS = 5;
    private static final String INSERT_SQL =
            "INSERT INTO users (name, email, phone, created_at, updated_at) VALUES (?, ?, ?, ?, ?)";
    private static final String[] FIRST_NAMES = {"Aarav", "Priya", "Rahul", "Ananya", "Vikram", "Meera",
            "Arjun", "Kavya", "John", "Emma", "Liam", "Olivia", "Noah", "Sophia", "Ravi", "Divya"};
    private static final String[] LAST_NAMES = {"Kumar", "Sharma", "Patel", "Reddy", "Iyer", "Nair",
            "Smith", "Johnson", "Brown", "Williams", "Garcia", "Singh"};

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final int concurrency;

    public UserGenerationService(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
                                 DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        int pool = dataSource instanceof HikariDataSource h ? h.getMaximumPoolSize() : 10;
        // leave a few connections for normal API traffic while a big generation runs
        this.concurrency = Math.max(1, pool - 4);
    }

    public GenerateUsersResponse generate(int count, String emailPrefix) {
        String prefix = (emailPrefix == null || emailPrefix.isBlank()) ? "user" : emailPrefix.trim().toLowerCase();
        String batchTag = UUID.randomUUID().toString().substring(0, 8);
        int chunks = (count + CHUNK_SIZE - 1) / CHUNK_SIZE;
        Semaphore permits = new Semaphore(concurrency);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        log.info("Generating {} users in {} chunks of {} (concurrency {}, batch {})",
                count, chunks, CHUNK_SIZE, concurrency, batchTag);
        long start = System.nanoTime();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int c = 0; c < chunks; c++) {
                final int from = c * CHUNK_SIZE + 1;
                final int to = Math.min(count, from + CHUNK_SIZE - 1);
                executor.submit(() -> {
                    try {
                        permits.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        insertChunk(prefix, batchTag, from, to);
                        created.addAndGet(to - from + 1);
                    } catch (Exception e) {
                        if (errors.size() < MAX_SAMPLE_ERRORS) {
                            errors.add("users #" + from + "-" + to + ": " + e.getMessage());
                        }
                        log.warn("Chunk {}-{} failed: {}", from, to, e.getMessage());
                    } finally {
                        permits.release();
                        logProgress(done.incrementAndGet(), chunks, created.get(), start);
                    }
                });
            }
        } // close() waits for every virtual thread to finish
        long durationMs = Math.max(1, (System.nanoTime() - start) / 1_000_000);

        int ok = created.get();
        long rate = ok * 1000L / durationMs;
        log.info("Generated {}/{} users in {} ms ({} rows/s, batch {})", ok, count, durationMs, rate, batchTag);
        return new GenerateUsersResponse(count, ok, count - ok, durationMs, rate, chunks, CHUNK_SIZE,
                concurrency, "virtual-thread-per-chunk", List.copyOf(errors));
    }

    /** Inserts users [from..to] in one transaction, using JDBC batches. */
    private void insertChunk(String prefix, String batchTag, int from, int to) {
        Timestamp now = Timestamp.from(Instant.now());
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<Object[]> rows = new ArrayList<>(to - from + 1);
        for (int i = from; i <= to; i++) {
            rows.add(new Object[]{
                    FIRST_NAMES[rnd.nextInt(FIRST_NAMES.length)] + " " + LAST_NAMES[rnd.nextInt(LAST_NAMES.length)],
                    prefix + "." + batchTag + "." + i + "@example.com",
                    "+91 9" + String.format("%09d", rnd.nextInt(1_000_000_000)),
                    now, now});
        }
        transactionTemplate.executeWithoutResult(tx -> {
            for (int i = 0; i < rows.size(); i += JDBC_BATCH_SIZE) {
                jdbcTemplate.batchUpdate(INSERT_SQL, rows.subList(i, Math.min(rows.size(), i + JDBC_BATCH_SIZE)));
            }
        });
    }

    /** Logs progress roughly every 5% for large runs. */
    private static void logProgress(int done, int chunks, int created, long start) {
        int step = Math.max(1, chunks / 20);
        if (chunks >= 20 && (done % step == 0 || done == chunks)) {
            long ms = Math.max(1, (System.nanoTime() - start) / 1_000_000);
            log.info("Generation progress: {}/{} chunks, {} users, {} rows/s", done, chunks, created,
                    created * 1000L / ms);
        }
    }
}
