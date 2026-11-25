package com.example.userservice.service;

import com.example.userservice.dto.GenerateUsersResponse;
import com.example.userservice.entity.User;
import com.example.userservice.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates and inserts N users concurrently, one virtual thread per user.
 * <p>
 * Virtual threads are cheap, so spawning thousands of them is fine. The real limit on
 * parallel inserts is the JDBC connection pool (DB_POOL_SIZE): threads waiting for a
 * connection park without blocking an OS thread.
 */
@Service
public class UserGenerationService {

    private static final Logger log = LoggerFactory.getLogger(UserGenerationService.class);
    private static final int MAX_SAMPLE_ERRORS = 5;
    private static final String[] FIRST_NAMES = {"Aarav", "Priya", "Rahul", "Ananya", "Vikram", "Meera",
            "Arjun", "Kavya", "John", "Emma", "Liam", "Olivia", "Noah", "Sophia", "Ravi", "Divya"};
    private static final String[] LAST_NAMES = {"Kumar", "Sharma", "Patel", "Reddy", "Iyer", "Nair",
            "Smith", "Johnson", "Brown", "Williams", "Garcia", "Singh"};

    private final UserRepository userRepository;

    public UserGenerationService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public GenerateUsersResponse generate(int count, String emailPrefix) {
        String prefix = (emailPrefix == null || emailPrefix.isBlank()) ? "user" : emailPrefix.trim().toLowerCase();
        String batchTag = UUID.randomUUID().toString().substring(0, 8);
        AtomicInteger created = new AtomicInteger();
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        long start = System.nanoTime();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(count);
            for (int i = 1; i <= count; i++) {
                final int index = i;
                futures.add(executor.submit(() -> {
                    try {
                        userRepository.save(randomUser(prefix, batchTag, index));
                        created.incrementAndGet();
                    } catch (Exception e) {
                        if (errors.size() < MAX_SAMPLE_ERRORS) {
                            errors.add("user #" + index + ": " + e.getMessage());
                        }
                        throw e;
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException ignored) {
                    // already counted and recorded above
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } // try-with-resources close() waits for all virtual threads to finish
        long durationMs = (System.nanoTime() - start) / 1_000_000;

        int ok = created.get();
        log.info("Generated {}/{} users with virtual threads in {} ms (batch {})", ok, count, durationMs, batchTag);
        return new GenerateUsersResponse(count, ok, count - ok, durationMs,
                "virtual-thread-per-task", List.copyOf(errors));
    }

    private static User randomUser(String prefix, String batchTag, int index) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        String name = FIRST_NAMES[rnd.nextInt(FIRST_NAMES.length)] + " " + LAST_NAMES[rnd.nextInt(LAST_NAMES.length)];
        String email = prefix + "." + batchTag + "." + index + "@example.com";
        String phone = "+91 9" + String.format("%09d", rnd.nextInt(1_000_000_000));
        return new User(name, email, phone);
    }
}
