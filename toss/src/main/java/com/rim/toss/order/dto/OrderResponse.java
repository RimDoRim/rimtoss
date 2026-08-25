package com.rim.toss.order.dto;

import java.math.BigDecimal;

import com.rim.toss.order.Order;
import com.rim.toss.order.OrderSide;
import com.rim.toss.order.OrderStatus;
import com.rim.toss.order.OrderType;

public record OrderResponse(
        Long id,
        String market,
        String symbol,
        OrderSide side,
        OrderType type,
        BigDecimal price,
        long quantity,
        long filledQuantity,
        OrderStatus status
) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(), order.getMarket(), order.getSymbol(), order.getSide(), order.getType(),
                order.getPrice(), order.getQuantity(), order.getFilledQuantity(), order.getStatus()
        );
    }
}
