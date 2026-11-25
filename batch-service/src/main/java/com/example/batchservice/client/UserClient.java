package com.example.batchservice.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/** HTTP client for user-service (/api/users). */
@Component
public class UserClient {

    public record UserDto(Long id, String name, String email, String phone) {
    }

    public record UserKey(Long id, String email) {
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

    /** Keyset page: the next {@code limit} users with id > afterId, ordered by id. */
    public List<UserKey> getKeysAfter(long afterId, int limit) {
        return executor.execute("GET user ids after " + afterId, () -> restClient.get()
                .uri(b -> b.path("/api/users/ids").queryParam("afterId", afterId).queryParam("limit", limit).build())
                .retrieve()
                .body(new ParameterizedTypeReference<List<UserKey>>() {
                }));
    }

    public UserDto getByEmail(String email) {
        return executor.execute("GET user by email " + email, () -> restClient.get()
                .uri(b -> b.path("/api/users/by-email").queryParam("email", email).build())
                .retrieve()
                .body(UserDto.class));
    }
}
