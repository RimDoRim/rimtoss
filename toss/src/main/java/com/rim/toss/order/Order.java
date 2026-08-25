package com.rim.toss.order;

import java.math.BigDecimal;

import com.rim.toss.account.Account;
import com.rim.toss.common.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 지정가/시장가 주문 1건. 실제 체결(매칭) 로직은 아직 없고,
 * 체결 서비스가 이 엔티티의 fill()/cancel()을 호출하는 형태로 이어붙일 예정이다.
 * 테이블명은 "order"가 예약어라 "orders"로 둔다.
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(nullable = false, length = 10)
    private String market;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OrderType type;

    /** 지정가 주문에서만 사용. 시장가 주문은 null. */
    @Column(precision = 19, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private long quantity;

    @Column(nullable = false)
    private long filledQuantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Builder
    private Order(Account account, String market, String symbol, OrderSide side,
                   OrderType type, BigDecimal price, long quantity) {
        if (type == OrderType.LIMIT && price == null) {
            throw new IllegalArgumentException("지정가 주문은 가격이 필요합니다.");
        }
        this.account = account;
        this.market = market;
        this.symbol = symbol;
        this.side = side;
        this.type = type;
        this.price = price;
        this.quantity = quantity;
        this.filledQuantity = 0L;
        this.status = OrderStatus.PENDING;
    }

    public void fill(long filledQty) {
        this.filledQuantity += filledQty;
        this.status = (this.filledQuantity >= this.quantity) ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }

    public void cancel() {
        if (this.status == OrderStatus.FILLED) {
            throw new IllegalStateException("이미 체결 완료된 주문은 취소할 수 없습니다.");
        }
        this.status = OrderStatus.CANCELLED;
    }
}
