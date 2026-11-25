package com.example.batchservice.entity;

/** The hop in the propagation chain: user-service -> order-service -> payment-service. */
public enum Stage {
    USER_LOOKUP, ORDER_CREATION, PAYMENT_CREATION
}
