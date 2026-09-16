package com.mricotta.circuitbreaker.order.service;

import com.mricotta.circuitbreaker.order.client.InventoryClient;
import com.mricotta.circuitbreaker.order.dto.OrderRequest;
import com.mricotta.circuitbreaker.order.dto.OrderResponse;
import com.mricotta.circuitbreaker.order.entity.OrderStatus;
import com.mricotta.circuitbreaker.order.exception.OutOfStockException;
import com.mricotta.circuitbreaker.order.mapper.OrderMapper;
import com.mricotta.circuitbreaker.order.repository.OrderRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;
    private final InventoryClient inventoryClient;

    /**
     * Deliberately not {@code @Transactional}: wrapping the remote call would hold a JDBC connection
     * for the whole read timeout, which is exactly the situation the fault injection creates. The
     * save below runs in its own implicit transaction, which is all this needs.
     */
    @Override
    public OrderResponse createOrder(OrderRequest request) {
        var stock = inventoryClient.checkStock(request.productId(), request.quantity());
        if (!stock.inStock()) {
            throw new OutOfStockException(request.productId(), request.quantity(), stock.available());
        }
        var order = orderMapper.toEntity(request);
        order.setStatus(OrderStatus.CONFIRMED);
        return orderMapper.toDto(orderRepository.save(order));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderResponse> listOrders() {
        return orderMapper.toDtoList(orderRepository.findAllByOrderByIdAsc());
    }
}
