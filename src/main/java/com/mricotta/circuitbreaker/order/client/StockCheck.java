package com.mricotta.circuitbreaker.order.client;

/**
 * This service's own view of the inventory response. Kept separate from any inventory DTO on purpose
 * so the two services stay independently deployable.
 */
public record StockCheck(
        Long productId,
        int requested,
        int available,
        boolean inStock) {
}
