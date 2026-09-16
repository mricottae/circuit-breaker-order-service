package com.mricotta.circuitbreaker.order.exception;

/**
 * The inventory service answered normally and said there is not enough stock. This is a business
 * outcome, not a failure of the dependency, so a circuit breaker must never count it.
 */
public class OutOfStockException extends RuntimeException {

    public OutOfStockException(Long productId, int requested, int available) {
        super("Product %d has %d units available, %d requested".formatted(productId, available, requested));
    }
}
