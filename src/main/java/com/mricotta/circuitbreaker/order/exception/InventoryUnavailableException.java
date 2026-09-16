package com.mricotta.circuitbreaker.order.exception;

/**
 * The inventory service could not be reached or answered with an error: connection refused, timeout
 * or 5xx. This is the failure a circuit breaker is meant to react to.
 */
public class InventoryUnavailableException extends RuntimeException {

    public InventoryUnavailableException(Long productId, Throwable cause) {
        super("Inventory service is unavailable while checking product %d".formatted(productId), cause);
    }
}
