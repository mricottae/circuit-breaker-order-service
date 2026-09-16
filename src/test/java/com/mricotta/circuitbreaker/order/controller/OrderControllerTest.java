package com.mricotta.circuitbreaker.order.controller;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mricotta.circuitbreaker.order.dto.OrderRequest;
import com.mricotta.circuitbreaker.order.dto.OrderResponse;
import com.mricotta.circuitbreaker.order.entity.OrderStatus;
import com.mricotta.circuitbreaker.order.exception.InventoryUnavailableException;
import com.mricotta.circuitbreaker.order.exception.OutOfStockException;
import com.mricotta.circuitbreaker.order.service.OrderService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    private static final String VALID_BODY = """
            {"productId":1,"quantity":2}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @Test
    void createOrder_returnsCreatedWithLocationAndBody() throws Exception {
        given(orderService.createOrder(any(OrderRequest.class))).willReturn(response(1L));

        mockMvc.perform(post("/v1/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/v1/orders/1")))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.productId").value(1))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void createOrder_withInvalidBody_returnsBadRequestWithFieldErrors() throws Exception {
        var invalidBody = """
                {"productId":null,"quantity":0}
                """;

        mockMvc.perform(post("/v1/orders").contentType(MediaType.APPLICATION_JSON).content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.productId").exists())
                .andExpect(jsonPath("$.fieldErrors.quantity").exists());

        verifyNoInteractions(orderService);
    }

    @Test
    void createOrder_whenOutOfStock_returnsConflict() throws Exception {
        given(orderService.createOrder(any(OrderRequest.class)))
                .willThrow(new OutOfStockException(1L, 2, 0));

        mockMvc.perform(post("/v1/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Product 1 has 0 units available, 2 requested"));
    }

    @Test
    void createOrder_whenInventoryUnavailable_returnsServiceUnavailable() throws Exception {
        given(orderService.createOrder(any(OrderRequest.class)))
                .willThrow(InventoryUnavailableException.callFailed(1L, new IllegalStateException("boom")));

        mockMvc.perform(post("/v1/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.path").value("/v1/orders"));
    }

    @Test
    void listOrders_returnsOkWithEveryOrder() throws Exception {
        given(orderService.listOrders()).willReturn(List.of(response(1L), response(2L)));

        mockMvc.perform(get("/v1/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[1].id").value(2));
    }

    private static OrderResponse response(Long id) {
        return new OrderResponse(id, 1L, 2, OrderStatus.CONFIRMED, Instant.now());
    }
}
