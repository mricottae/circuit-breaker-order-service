package com.mricotta.circuitbreaker.order.mapper;

import com.mricotta.circuitbreaker.order.dto.OrderRequest;
import com.mricotta.circuitbreaker.order.dto.OrderResponse;
import com.mricotta.circuitbreaker.order.entity.Order;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface OrderMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    Order toEntity(OrderRequest request);

    OrderResponse toDto(Order order);

    List<OrderResponse> toDtoList(List<Order> orders);
}
