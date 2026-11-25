package com.example.userservice.controller;

import com.example.userservice.dto.GenerateUsersRequest;
import com.example.userservice.dto.GenerateUsersResponse;
import com.example.userservice.service.UserGenerationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Bulk-creates users concurrently using Java 21 virtual threads.
 */
@RestController
@RequestMapping("/api/users/generate")
public class UserGenerationController {

    private final UserGenerationService generationService;

    public UserGenerationController(UserGenerationService generationService) {
        this.generationService = generationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GenerateUsersResponse generate(@Valid @RequestBody GenerateUsersRequest request) {
        return generationService.generate(request.count(), request.emailPrefix());
    }
}
