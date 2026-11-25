package com.example.batchservice.entity;

public enum RequestType {
    /** POST /api/propagate with explicit user ids / emails. */
    MANUAL,
    /** POST /api/propagate/bulk: the next N not-yet-propagated users are selected from user-service. */
    BULK
}
