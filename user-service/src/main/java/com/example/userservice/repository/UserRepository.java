package com.example.userservice.repository;

import com.example.userservice.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    // Emails are stored lowercase (see UserService), so exact matches use the unique index on email.
    // An "IgnoreCase" query would wrap the column in lower() and scan the whole table.
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
