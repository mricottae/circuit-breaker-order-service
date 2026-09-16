package com.mricotta.circuitbreaker.order.service;

import com.mricotta.circuitbreaker.order.dto.OrderRequest;
import com.mricotta.circuitbreaker.order.dto.OrderResponse;
import java.util.List;

public interface OrderService {

    OrderResponse createOrder(OrderRequest request);

    List<OrderResponse> listOrders();
}
