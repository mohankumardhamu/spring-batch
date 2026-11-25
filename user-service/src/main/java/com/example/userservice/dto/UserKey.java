package com.example.userservice.dto;

/** Lightweight id + email projection used for bulk reads (keyset pagination). */
public record UserKey(Long id, String email) {
}
