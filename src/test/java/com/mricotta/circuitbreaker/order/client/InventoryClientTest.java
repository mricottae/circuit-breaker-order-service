package com.mricotta.circuitbreaker.order.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.mricotta.circuitbreaker.order.exception.InventoryUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class InventoryClientTest {

    private MockRestServiceServer server;
    private InventoryClient inventoryClient;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl("http://inventory.test");
        server = MockRestServiceServer.bindTo(builder).build();
        inventoryClient = new InventoryClient(builder.build());
    }

    @Test
    void checkStock_whenInventoryAnswers_parsesTheStockCheck() {
        var body = """
                {"productId":1,"requested":2,"available":10,"inStock":true}
                """;
        server.expect(requestTo("http://inventory.test/v1/inventory/1?quantity=2"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        var stock = inventoryClient.checkStock(1L, 2);

        assertThat(stock.productId()).isEqualTo(1L);
        assertThat(stock.available()).isEqualTo(10);
        assertThat(stock.inStock()).isTrue();
        server.verify();
    }

    @Test
    void checkStockFallback_whenCircuitIsOpen_reportsThatNoCallWasAttempted() {
        var breaker = CircuitBreaker.ofDefaults("inventory");
        breaker.transitionToOpenState();

        assertThatThrownBy(() -> inventoryClient.checkStockFallback(
                        1L, 2, CallNotPermittedException.createCallNotPermittedException(breaker)))
                .isInstanceOf(InventoryUnavailableException.class)
                .hasMessage("Inventory circuit breaker is open; stock check for product 1 was not attempted");
    }

    @Test
    void checkStockFallback_whenTheCallFailed_rethrowsTheOriginalWithoutRewrapping() {
        var original = InventoryUnavailableException.callFailed(1L, new IllegalStateException("boom"));

        assertThatThrownBy(() -> inventoryClient.checkStockFallback(1L, 2, original))
                .isSameAs(original);
    }

    @Test
    void checkStock_whenInventoryReturnsServerError_throwsInventoryUnavailable() {
        server.expect(requestTo("http://inventory.test/v1/inventory/1?quantity=2"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> inventoryClient.checkStock(1L, 2))
                .isInstanceOf(InventoryUnavailableException.class)
                .hasMessage("Inventory service is unavailable while checking product 1");

        server.verify();
    }
}
