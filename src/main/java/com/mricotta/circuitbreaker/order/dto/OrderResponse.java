package com.mricotta.circuitbreaker.order.dto;

import com.mricotta.circuitbreaker.order.entity.OrderStatus;
import java.time.Instant;

public record OrderResponse(
        Long id,
        Long productId,
        Integer quantity,
        OrderStatus status,
        Instant createdAt) {
}
