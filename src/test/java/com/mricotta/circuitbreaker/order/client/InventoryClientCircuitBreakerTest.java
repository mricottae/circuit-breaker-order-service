package com.mricotta.circuitbreaker.order.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.mricotta.circuitbreaker.order.exception.InventoryUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

/**
 * The one test in this service that needs a Spring context. The circuit breaker is applied by an AOP
 * proxy, so without the container there is no breaker to exercise — only the plain try/catch inside
 * {@link InventoryClient}, which {@code InventoryClientTest} already covers.
 *
 * <p>Thresholds are overridden here so the state machine can be driven in three calls instead of five.
 */
@SpringBootTest(properties = {
        "resilience4j.circuitbreaker.instances.inventory.sliding-window-size=3",
        "resilience4j.circuitbreaker.instances.inventory.minimum-number-of-calls=3",
        "resilience4j.circuitbreaker.instances.inventory.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.inventory.wait-duration-in-open-state=60s",
        "resilience4j.circuitbreaker.instances.inventory.automatic-transition-from-open-to-half-open-enabled=false"
})
class InventoryClientCircuitBreakerTest {

    @Autowired
    private InventoryClient inventoryClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @MockitoBean
    private RestClient inventoryRestClient;

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        circuitBreaker = circuitBreakerRegistry.circuitBreaker("inventory");
        circuitBreaker.reset();
        // Every outgoing call blows up on the first builder step, which checkStock wraps as a failure.
        given(inventoryRestClient.get()).willThrow(new ResourceAccessException("connection refused"));
    }

    @Test
    void checkStock_isIntercepted_soFailuresAreRecordedByTheBreaker() {
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        assertThatThrownBy(() -> inventoryClient.checkStock(1L, 2))
                .isInstanceOf(InventoryUnavailableException.class);

        // If the AOP proxy were missing, the aspect would never run and this would still be zero.
        assertThat(circuitBreaker.getMetrics().getNumberOfBufferedCalls()).isEqualTo(1);
    }

    @Test
    void checkStock_afterEnoughFailures_opensTheCircuitAndStopsCallingOut() {
        for (var i = 0; i < 3; i++) {
            assertThatThrownBy(() -> inventoryClient.checkStock(1L, 2))
                    .isInstanceOf(InventoryUnavailableException.class);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> inventoryClient.checkStock(1L, 2))
                .isInstanceOf(InventoryUnavailableException.class)
                .hasMessageContaining("circuit breaker is open");

        // The rejected call never reached the dependency: the buffer still holds only the three above.
        assertThat(circuitBreaker.getMetrics().getNumberOfBufferedCalls()).isEqualTo(3);
        assertThat(circuitBreaker.getMetrics().getNumberOfNotPermittedCalls()).isEqualTo(1L);
    }
}
