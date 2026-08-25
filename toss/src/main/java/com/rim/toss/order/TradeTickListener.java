package com.rim.toss.order;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * toss-collector가 발행하고 distribution-server가 중계하는 것과 동일한
 * Redis pub/sub 채널("trade:{market}:{symbol}")을 직접 구독한다. 새 시세가
 * 들어올 때마다 그 종목에 대해 대기 중인 지정가 주문을 재시도한다.
 *
 * 주문 1건씩 별도 트랜잭션(OrderExecutionService.tryExecute의 @Transactional)으로
 * 처리하므로, 한 주문 체결이 실패해도 같은 틱에서 다른 주문 처리에는 영향을 주지 않는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TradeTickListener implements MessageListener {

    private static final List<OrderStatus> ACTIVE_STATUSES = List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);

    private final OrderRepository orderRepository;
    private final OrderExecutionService orderExecutionService;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        // channel 예: "trade:kr:005930"
        String[] parts = channel.split(":", 3);
        if (parts.length != 3 || !"trade".equals(parts[0])) {
            return;
        }
        String market = parts[1];
        String symbol = parts[2];

        List<Order> pendingOrders = orderRepository.findByMarketAndSymbolAndStatusIn(market, symbol, ACTIVE_STATUSES);
        for (Order order : pendingOrders) {
            try {
                orderExecutionService.tryExecute(order.getId());
            } catch (RuntimeException e) {
                log.warn("시세 틱에 의한 체결 재시도 실패: orderId={}", order.getId(), e);
            }
        }
    }
}
