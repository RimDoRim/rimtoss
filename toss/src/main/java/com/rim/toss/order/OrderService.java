package com.rim.toss.order;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rim.toss.account.Account;
import com.rim.toss.account.AccountRepository;
import com.rim.toss.account.Holding;
import com.rim.toss.account.HoldingRepository;
import com.rim.toss.marketdata.MarketDataService;
import com.rim.toss.order.dto.ExecutionResponse;
import com.rim.toss.order.dto.OrderResponse;
import com.rim.toss.order.dto.PlaceOrderRequest;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final ExecutionRepository executionRepository;
    private final AccountRepository accountRepository;
    private final HoldingRepository holdingRepository;
    private final MarketDataService marketDataService;
    private final OrderExecutionService orderExecutionService;

    /**
     * 주문을 생성하고 같은 트랜잭션 안에서 즉시 체결을 1회 시도한다.
     * 시장가는 이 시점에 체결되지 않으면 예외로 주문 생성 자체가 롤백된다(거부).
     * 지정가가 조건을 못 맞추면 PENDING으로 남고, 이후 TradeTickListener가
     * 새 시세가 올 때마다 재시도한다.
     *
     * 참고: 지정가 대기 주문에 대해 현금/보유수량을 별도로 잠그지 않는다(에스크로 없음).
     * 그래서 이론상 지정가 매수를 여러 건 걸어두고 그 사이에 다른 곳에 현금을 다 쓰면
     * 나중에 조건이 맞아도 체결 시점에 잔고 부족으로 실패할 수 있다 - 다음 단계 개선 후보.
     */
    @Transactional
    public Long placeOrder(String phoneNumber, PlaceOrderRequest request) {
        validate(request);
        Account account = accountRepository.findByMemberPhoneNumber(phoneNumber)
                .orElseThrow(() -> new IllegalStateException("계좌를 찾을 수 없습니다."));

        if (request.side() == OrderSide.BUY) {
            checkBuyingPower(account, request);
        } else {
            checkHoldingQuantity(account, request);
        }

        Order order = Order.builder()
                .account(account)
                .market(request.market())
                .symbol(request.symbol())
                .side(request.side())
                .type(request.type())
                .price(request.type() == OrderType.LIMIT ? request.price() : null)
                .quantity(request.quantity())
                .build();
        orderRepository.save(order);

        orderExecutionService.tryExecute(order.getId());

        return order.getId();
    }

    @Transactional
    public void cancelOrder(String phoneNumber, Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 주문입니다: " + orderId));
        if (!order.getAccount().getMember().getPhoneNumber().equals(phoneNumber)) {
            throw new IllegalArgumentException("본인 주문만 취소할 수 있습니다.");
        }
        order.cancel();
    }

    public List<OrderResponse> getMyOrders(String phoneNumber) {
        Account account = accountRepository.findByMemberPhoneNumber(phoneNumber)
                .orElseThrow(() -> new IllegalStateException("계좌를 찾을 수 없습니다."));
        return orderRepository.findByAccountIdOrderByCreatedAtDesc(account.getId())
                .stream()
                .map(OrderResponse::from)
                .toList();
    }

    public List<ExecutionResponse> getExecutions(String phoneNumber, Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 주문입니다: " + orderId));
        if (!order.getAccount().getMember().getPhoneNumber().equals(phoneNumber)) {
            throw new IllegalArgumentException("본인 주문만 조회할 수 있습니다.");
        }
        return executionRepository.findByOrderIdOrderByCreatedAtAsc(orderId)
                .stream()
                .map(ExecutionResponse::from)
                .toList();
    }

    private void checkBuyingPower(Account account, PlaceOrderRequest request) {
        BigDecimal guardPrice = request.type() == OrderType.LIMIT
                ? request.price()
                : marketDataService.getLatestPrice(request.market(), request.symbol())
                    .orElseThrow(() -> new IllegalStateException("현재가를 조회할 수 없어 시장가 매수 주문을 넣을 수 없습니다."));

        BigDecimal requiredCash = guardPrice.multiply(BigDecimal.valueOf(request.quantity()));
        if (account.getCashBalance().compareTo(requiredCash) < 0) {
            throw new IllegalStateException("주문 가능 현금이 부족합니다.");
        }
    }

    private void checkHoldingQuantity(Account account, PlaceOrderRequest request) {
        Holding holding = holdingRepository
                .findByAccountIdAndMarketAndSymbol(account.getId(), request.market(), request.symbol())
                .orElse(null);
        if (holding == null || holding.getQuantity() < request.quantity()) {
            throw new IllegalStateException("보유 수량이 부족하여 매도 주문을 넣을 수 없습니다.");
        }
    }

    private void validate(PlaceOrderRequest request) {
        if (request.market() == null || request.market().isBlank()) {
            throw new IllegalArgumentException("market은 필수입니다.");
        }
        if (request.symbol() == null || request.symbol().isBlank()) {
            throw new IllegalArgumentException("symbol은 필수입니다.");
        }
        if (request.quantity() <= 0) {
            throw new IllegalArgumentException("수량은 1 이상이어야 합니다.");
        }
        if (request.type() == OrderType.LIMIT && (request.price() == null || request.price().signum() <= 0)) {
            throw new IllegalArgumentException("지정가 주문은 가격이 0보다 커야 합니다.");
        }
    }
}
