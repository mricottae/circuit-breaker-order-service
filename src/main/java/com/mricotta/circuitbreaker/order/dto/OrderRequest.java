package com.mricotta.circuitbreaker.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record OrderRequest(
        @NotNull Long productId,
        @NotNull @Positive Integer quantity) {
}
