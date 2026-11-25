package com.example.batchservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Single-row table holding the highest user id already claimed by bulk propagation.
 * The next bulk request continues after it, so walking 10M users never rescans from the start.
 * Rows are locked with SELECT ... FOR UPDATE so concurrent bulk requests never claim the same users.
 */
@Entity
@Table(name = "bulk_propagation_cursor")
public class BulkCursor {

    public static final int SINGLETON_ID = 1;

    @Id
    private Integer id;

    @Column(nullable = false)
    private long lastUserId;

    protected BulkCursor() {
    }

    public Integer getId() { return id; }
    public long getLastUserId() { return lastUserId; }
}
