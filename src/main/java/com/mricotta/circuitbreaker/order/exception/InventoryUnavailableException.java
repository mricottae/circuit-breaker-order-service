package com.mricotta.circuitbreaker.order.exception;

/**
 * The stock for an order could not be established. Two distinct causes, kept apart because they read
 * very differently in a log: the call went out and failed, or the circuit breaker rejected it before
 * it ever left this service.
 */
public class InventoryUnavailableException extends RuntimeException {

    private InventoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Connection refused, timeout, or a 5xx from the inventory service. */
    public static InventoryUnavailableException callFailed(Long productId, Throwable cause) {
        return new InventoryUnavailableException(
                "Inventory service is unavailable while checking product %d".formatted(productId), cause);
    }

    /** The breaker is open, so no call was attempted at all. */
    public static InventoryUnavailableException circuitOpen(Long productId, Throwable cause) {
        return new InventoryUnavailableException(
                "Inventory circuit breaker is open; stock check for product %d was not attempted"
                        .formatted(productId), cause);
    }
}
