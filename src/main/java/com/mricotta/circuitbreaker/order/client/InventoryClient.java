package com.mricotta.circuitbreaker.order.client;

import com.mricotta.circuitbreaker.order.exception.InventoryUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryClient {

    private final RestClient inventoryRestClient;

    /**
     * The only remote call this service makes, and therefore the seam a circuit breaker belongs on.
     *
     * <p>One catch covers every remote problem: {@code HttpServerErrorException} for the injected
     * 500, and {@code ResourceAccessException} for a timeout or a refused connection. Both extend
     * {@link RestClientException}.
     */
    public StockCheck checkStock(Long productId, int quantity) {
        try {
            return inventoryRestClient.get()
                    .uri("/v1/inventory/{productId}?quantity={quantity}", productId, quantity)
                    .retrieve()
                    .body(StockCheck.class);
        } catch (RestClientException ex) {
            log.warn("Stock check for product {} failed: {}", productId, ex.getMessage());
            throw new InventoryUnavailableException(productId, ex);
        }
    }
}
