package com.rim.toss.order;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByAccountIdOrderByCreatedAtDesc(Long accountId);

    List<Order> findByStatus(OrderStatus status);

    List<Order> findByMarketAndSymbolAndStatusIn(String market, String symbol, List<OrderStatus> statuses);
}
