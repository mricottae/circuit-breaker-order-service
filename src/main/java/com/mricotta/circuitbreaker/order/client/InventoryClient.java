package com.mricotta.circuitbreaker.order.client;

import com.mricotta.circuitbreaker.order.exception.InventoryUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryClient {

    static final String CIRCUIT_BREAKER_NAME = "inventory";

    private final RestClient inventoryRestClient;

    /**
     * The only remote call this service makes, and therefore the seam the circuit breaker sits on.
     *
     * <p>One catch covers every remote problem: {@code HttpServerErrorException} for the injected
     * 500, and {@code ResourceAccessException} for a timeout or a refused connection. Both extend
     * {@link RestClientException}.
     */
    @CircuitBreaker(name = CIRCUIT_BREAKER_NAME, fallbackMethod = "checkStockFallback")
    public StockCheck checkStock(Long productId, int quantity) {
        try {
            return inventoryRestClient.get()
                    .uri("/v1/inventory/{productId}?quantity={quantity}", productId, quantity)
                    .retrieve()
                    .body(StockCheck.class);
        } catch (RestClientException ex) {
            log.warn("Stock check for product {} failed: {}", productId, ex.getMessage());
            throw InventoryUnavailableException.callFailed(productId, ex);
        }
    }

    /**
     * Resilience4j requires the same signature plus a trailing throwable. It is reached both when the
     * call actually failed and when the breaker is open and rejected it without calling anything.
     *
     * <p>It rethrows instead of returning a degraded {@code StockCheck}: without knowing the stock an
     * order cannot be confirmed, and inventing an answer would either reject valid orders or accept
     * ones that cannot be fulfilled. The caller still gets a 503 — only far faster, and without
     * touching the failing service.
     */
    StockCheck checkStockFallback(Long productId, int quantity, Throwable cause) {
        if (cause instanceof CallNotPermittedException) {
            // Not an incident: this is the breaker shedding load off a dependency already known to be bad.
            log.warn("Circuit '{}' is open; skipping stock check for product {}", CIRCUIT_BREAKER_NAME, productId);
            throw InventoryUnavailableException.circuitOpen(productId, cause);
        }
        if (cause instanceof InventoryUnavailableException ex) {
            // Already wrapped by checkStock; rethrow so the cause chain stays flat.
            throw ex;
        }
        throw InventoryUnavailableException.callFailed(productId, cause);
    }
}
