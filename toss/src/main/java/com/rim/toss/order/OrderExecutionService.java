package com.rim.toss.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rim.toss.account.Account;
import com.rim.toss.account.Holding;
import com.rim.toss.account.HoldingRepository;
import com.rim.toss.marketdata.MarketDataService;
import com.rim.toss.marketdata.dto.TradeSnapshot;

import lombok.RequiredArgsConstructor;

/**
 * 주문 1건을 최근 체결(trade) 틱과 대조해 체결을 시도한다. 실제 상대 주문자가 없는 가상 투자라
 * distribution-server가 중계하는 체결 틱만을 상대로 체결하며, 부분체결을 지원하기 위해 한 번의
 * 시도(tryExecute 호출 1회)에서는 "가장 최근 체결 틱의 거래량(volume)"만큼만 채운다 - 실제 시장에서
 * 내 주문이 그 틱의 물량을 통째로 다 가져가지는 못하는 것과 비슷하게, 한 틱이 감당할 수 있는 만큼만
 * 체결시키고 나머지는 다음 틱에서 마저 채운다(TradeTickListener가 새 체결마다 재시도를 걸어준다).
 *
 *  - 시장가: 가격 조건 없이 항상 매칭. 틱 거래량만큼만 부분체결하고, 다 못 채우면 주문을 취소하지
 *    않고 PENDING/PARTIALLY_FILLED로 남겨 다음 틱을 기다린다.
 *  - 지정가: 틱 가격이 조건을 만족할 때만 매칭. 체결가는 틱 가격이 아니라 항상 자신의 지정가로
 *    고정한다 - 지정가보다 불리하게 체결되는 일이 없도록 하기 위함.
 *  - 시세 자체가 없으면(getLatestTrade 없음) 시장가는 체결 불가 예외, 지정가는 그냥 대기.
 *
 * 항상 orderId로 새로 조회해서 동작한다 - 호출자(같은 트랜잭션에서 부르는 OrderService,
 * 별도 트랜잭션에서 부르는 TradeTickListener 양쪽 모두)와 무관하게 준영속 엔티티 문제 없이
 * 안전하게 재사용하기 위함이다.
 */
@Service
@RequiredArgsConstructor
public class OrderExecutionService {

    private static final List<OrderStatus> ACTIVE_STATUSES = List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);

    private final OrderRepository orderRepository;
    private final ExecutionRepository executionRepository;
    private final HoldingRepository holdingRepository;
    private final MarketDataService marketDataService;

    @Transactional
    public void tryExecute(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 주문입니다: " + orderId));

        if (!ACTIVE_STATUSES.contains(order.getStatus())) {
            return;
        }

        if (order.getType() == OrderType.MARKET) {
            TradeSnapshot trade = marketDataService.getLatestTrade(order.getMarket(), order.getSymbol())
                    .orElseThrow(() -> new IllegalStateException("현재가를 조회할 수 없어 시장가 주문을 체결할 수 없습니다."));
            fillUpTo(order, trade.price(), trade.volume());
            return;
        }

        marketDataService.getLatestTrade(order.getMarket(), order.getSymbol())
                .filter(trade -> isMatch(order, trade.price()))
                .ifPresent(trade -> fillUpTo(order, order.getPrice(), trade.volume()));
    }

    private boolean isMatch(Order order, BigDecimal tradePrice) {
        return order.getSide() == OrderSide.BUY
                ? tradePrice.compareTo(order.getPrice()) <= 0
                : tradePrice.compareTo(order.getPrice()) >= 0;
    }

    /** 남은 주문 수량과 이번 틱의 거래량 중 작은 쪽만큼만 체결시킨다(부분체결). */
    private void fillUpTo(Order order, BigDecimal execPrice, BigDecimal tickVolume) {
        long remaining = order.getQuantity() - order.getFilledQuantity();
        long availableQuantity = tickVolume.setScale(0, RoundingMode.DOWN).longValue();
        long fillQuantity = Math.min(remaining, availableQuantity);
        if (fillQuantity <= 0) {
            return;
        }
        fill(order, fillQuantity, execPrice);
    }

    private void fill(Order order, long fillQuantity, BigDecimal fillPrice) {
        Account account = order.getAccount();
        BigDecimal amount = fillPrice.multiply(BigDecimal.valueOf(fillQuantity));

        if (order.getSide() == OrderSide.BUY) {
            account.withdraw(amount);
            Holding holding = holdingRepository
                    .findByAccountIdAndMarketAndSymbol(account.getId(), order.getMarket(), order.getSymbol())
                    .orElseGet(() -> holdingRepository.save(Holding.builder()
                            .account(account)
                            .market(order.getMarket())
                            .symbol(order.getSymbol())
                            .quantity(0)
                            .avgPrice(BigDecimal.ZERO)
                            .build()));
            holding.applyBuy(fillQuantity, fillPrice);
        } else {
            Holding holding = holdingRepository
                    .findByAccountIdAndMarketAndSymbol(account.getId(), order.getMarket(), order.getSymbol())
                    .orElseThrow(() -> new IllegalStateException("보유 수량이 부족하여 매도 주문을 체결할 수 없습니다."));
            holding.applySell(fillQuantity);
            account.deposit(amount);
        }

        order.fill(fillQuantity);
        executionRepository.save(Execution.builder()
                .order(order)
                .quantity(fillQuantity)
                .price(fillPrice)
                .build());
    }
}
