package com.example.batchservice.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** HTTP client for user-service (/api/users). */
@Component
public class UserClient {

    public record UserDto(Long id, String name, String email, String phone) {
    }

    private final RestClient restClient;
    private final RemoteCallExecutor executor;

    public UserClient(@Qualifier("userRestClient") RestClient restClient, RemoteCallExecutor executor) {
        this.restClient = restClient;
        this.executor = executor;
    }

    public UserDto getById(Long id) {
        return executor.execute("GET user " + id, () -> restClient.get()
                .uri("/api/users/{id}", id)
                .retrieve()
                .body(UserDto.class));
    }

    public UserDto getByEmail(String email) {
        return executor.execute("GET user by email " + email, () -> restClient.get()
                .uri(b -> b.path("/api/users/by-email").queryParam("email", email).build())
                .retrieve()
                .body(UserDto.class));
    }
}
