package com.example.userservice.service;

import com.example.userservice.dto.UserRequest;
import com.example.userservice.dto.UserResponse;
import com.example.userservice.entity.User;
import com.example.userservice.exception.DuplicateResourceException;
import com.example.userservice.exception.ResourceNotFoundException;
import com.example.userservice.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public UserResponse create(UserRequest request) {
        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            throw new DuplicateResourceException("User with email " + request.email() + " already exists");
        }
        User saved = userRepository.save(new User(request.name(), request.email(), request.phone()));
        return UserResponse.from(saved);
    }

    public UserResponse getById(Long id) {
        return UserResponse.from(findOrThrow(id));
    }

    public UserResponse getByEmail(String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .map(UserResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email " + email));
    }

    public Page<UserResponse> getAll(Pageable pageable) {
        return userRepository.findAll(pageable).map(UserResponse::from);
    }

    @Transactional
    public UserResponse update(Long id, UserRequest request) {
        User user = findOrThrow(id);
        if (!user.getEmail().equalsIgnoreCase(request.email())
                && userRepository.existsByEmailIgnoreCase(request.email())) {
            throw new DuplicateResourceException("User with email " + request.email() + " already exists");
        }
        user.setName(request.name());
        user.setEmail(request.email());
        user.setPhone(request.phone());
        return UserResponse.from(userRepository.saveAndFlush(user));
    }

    @Transactional
    public void delete(Long id) {
        userRepository.delete(findOrThrow(id));
    }

    private User findOrThrow(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id " + id));
    }
}
