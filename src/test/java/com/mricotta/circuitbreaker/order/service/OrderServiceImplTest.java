package com.mricotta.circuitbreaker.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.mricotta.circuitbreaker.order.client.InventoryClient;
import com.mricotta.circuitbreaker.order.client.StockCheck;
import com.mricotta.circuitbreaker.order.dto.OrderRequest;
import com.mricotta.circuitbreaker.order.dto.OrderResponse;
import com.mricotta.circuitbreaker.order.entity.Order;
import com.mricotta.circuitbreaker.order.entity.OrderStatus;
import com.mricotta.circuitbreaker.order.exception.InventoryUnavailableException;
import com.mricotta.circuitbreaker.order.exception.OutOfStockException;
import com.mricotta.circuitbreaker.order.mapper.OrderMapper;
import com.mricotta.circuitbreaker.order.repository.OrderRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private InventoryClient inventoryClient;

    @Captor
    private ArgumentCaptor<Order> orderCaptor;

    @InjectMocks
    private OrderServiceImpl orderService;

    @Test
    void createOrder_whenInStock_savesConfirmedOrderAndReturnsDto() {
        var request = new OrderRequest(1L, 2);
        var entity = Order.builder().productId(1L).quantity(2).build();
        var expected = response(1L);
        given(inventoryClient.checkStock(1L, 2)).willReturn(new StockCheck(1L, 2, 10, true));
        given(orderMapper.toEntity(request)).willReturn(entity);
        given(orderRepository.save(entity)).willReturn(entity);
        given(orderMapper.toDto(entity)).willReturn(expected);

        assertThat(orderService.createOrder(request)).isEqualTo(expected);

        then(orderRepository).should().save(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(orderCaptor.getValue().getProductId()).isEqualTo(1L);
    }

    @Test
    void createOrder_whenOutOfStock_throwsAndPersistsNothing() {
        var request = new OrderRequest(3L, 5);
        given(inventoryClient.checkStock(3L, 5)).willReturn(new StockCheck(3L, 5, 0, false));

        assertThatThrownBy(() -> orderService.createOrder(request))
                .isInstanceOf(OutOfStockException.class)
                .hasMessage("Product 3 has 0 units available, 5 requested");

        then(orderRepository).shouldHaveNoInteractions();
    }

    @Test
    void createOrder_whenInventoryUnavailable_propagatesAndPersistsNothing() {
        var request = new OrderRequest(1L, 2);
        given(inventoryClient.checkStock(1L, 2))
                .willThrow(new InventoryUnavailableException(1L, new IllegalStateException("boom")));

        assertThatThrownBy(() -> orderService.createOrder(request))
                .isInstanceOf(InventoryUnavailableException.class);

        then(orderRepository).should(never()).save(any(Order.class));
        then(orderMapper).shouldHaveNoInteractions();
    }

    @Test
    void listOrders_returnsMappedOrders() {
        var orders = List.of(Order.builder()
                .id(1L)
                .productId(1L)
                .quantity(2)
                .status(OrderStatus.CONFIRMED)
                .createdAt(Instant.now())
                .build());
        var expected = List.of(response(1L));
        given(orderRepository.findAllByOrderByIdAsc()).willReturn(orders);
        given(orderMapper.toDtoList(orders)).willReturn(expected);

        assertThat(orderService.listOrders()).containsExactlyElementsOf(expected);
    }

    private static OrderResponse response(Long id) {
        return new OrderResponse(id, 1L, 2, OrderStatus.CONFIRMED, Instant.now());
    }
}
