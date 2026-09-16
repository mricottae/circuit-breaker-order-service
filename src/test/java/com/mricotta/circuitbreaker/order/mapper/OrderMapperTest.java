package com.mricotta.circuitbreaker.order.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.mricotta.circuitbreaker.order.dto.OrderRequest;
import com.mricotta.circuitbreaker.order.entity.Order;
import com.mricotta.circuitbreaker.order.entity.OrderStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class OrderMapperTest {

    private final OrderMapper orderMapper = Mappers.getMapper(OrderMapper.class);

    @Test
    void toEntity_copiesRequestAndLeavesManagedFieldsNull() {
        var entity = orderMapper.toEntity(new OrderRequest(1L, 3));

        assertThat(entity.getProductId()).isEqualTo(1L);
        assertThat(entity.getQuantity()).isEqualTo(3);
        assertThat(entity.getId()).isNull();
        assertThat(entity.getStatus()).isNull();
        assertThat(entity.getCreatedAt()).isNull();
    }

    @Test
    void toDto_copiesEveryField() {
        var createdAt = Instant.now();
        var dto = orderMapper.toDto(order(1L, 7L, createdAt));

        assertThat(dto.id()).isEqualTo(1L);
        assertThat(dto.productId()).isEqualTo(7L);
        assertThat(dto.quantity()).isEqualTo(1);
        assertThat(dto.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(dto.createdAt()).isEqualTo(createdAt);
    }

    @Test
    void toDtoList_mapsEveryOrder() {
        var orders = List.of(order(1L, 1L, Instant.now()), order(2L, 2L, Instant.now()));

        assertThat(orderMapper.toDtoList(orders))
                .extracting("id", "productId", "status")
                .containsExactly(
                        tuple(1L, 1L, OrderStatus.CONFIRMED),
                        tuple(2L, 2L, OrderStatus.CONFIRMED));
    }

    private static Order order(Long id, Long productId, Instant createdAt) {
        return Order.builder()
                .id(id)
                .productId(productId)
                .quantity(1)
                .status(OrderStatus.CONFIRMED)
                .createdAt(createdAt)
                .build();
    }
}
