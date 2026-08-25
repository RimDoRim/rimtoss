package com.rim.toss.order.dto;

import java.math.BigDecimal;

import com.rim.toss.order.OrderSide;
import com.rim.toss.order.OrderType;

public record PlaceOrderRequest(
        String market,
        String symbol,
        OrderSide side,
        OrderType type,
        BigDecimal price,
        long quantity
) {
}
