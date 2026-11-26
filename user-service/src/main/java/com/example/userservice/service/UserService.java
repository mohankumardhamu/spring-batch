package com.example.userservice.service;

import com.example.userservice.dto.UserKey;
import com.example.userservice.dto.UserRequest;
import com.example.userservice.dto.UserResponse;
import com.example.userservice.entity.User;
import com.example.userservice.exception.DuplicateResourceException;
import com.example.userservice.exception.ResourceNotFoundException;
import com.example.userservice.repository.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final Counter usersCreated;

    public UserService(UserRepository userRepository, MeterRegistry meterRegistry) {
        this.userRepository = userRepository;
        this.usersCreated = Counter.builder("users.added").description("Users added")
                .tag("source", "api").register(meterRegistry);
    }

    @Transactional
    public UserResponse create(UserRequest request) {
        String email = normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateResourceException("User with email " + email + " already exists");
        }
        User saved = userRepository.save(new User(request.name(), email, request.phone()));
        usersCreated.increment();
        return UserResponse.from(saved);
    }

    public UserResponse getById(Long id) {
        return UserResponse.from(findOrThrow(id));
    }

    public UserResponse getByEmail(String email) {
        return userRepository.findByEmail(normalize(email))
                .map(UserResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email " + email));
    }

    public java.util.List<UserKey> getKeysAfter(long afterId, int limit) {
        return userRepository.findByIdGreaterThanOrderByIdAsc(afterId, Limit.of(limit));
    }

    public long count() {
        return userRepository.count();
    }

    public Page<UserResponse> getAll(Pageable pageable) {
        return userRepository.findAll(pageable).map(UserResponse::from);
    }

    @Transactional
    public UserResponse update(Long id, UserRequest request) {
        User user = findOrThrow(id);
        String email = normalize(request.email());
        if (!user.getEmail().equals(email) && userRepository.existsByEmail(email)) {
            throw new DuplicateResourceException("User with email " + email + " already exists");
        }
        user.setName(request.name());
        user.setEmail(email);
        user.setPhone(request.phone());
        return UserResponse.from(userRepository.saveAndFlush(user));
    }

    @Transactional
    public void delete(Long id) {
        userRepository.delete(findOrThrow(id));
    }

    /** Emails are case-insensitive: store and look them up in lowercase. */
    private static String normalize(String email) {
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private User findOrThrow(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id " + id));
    }
}
