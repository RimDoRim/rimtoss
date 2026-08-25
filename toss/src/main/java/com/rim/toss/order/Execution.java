package com.rim.toss.order;

import java.math.BigDecimal;

import com.rim.toss.common.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 주문 1건에 대한 체결 1건의 기록(부분체결이면 여러 건 쌓인다).
 * 체결 시각은 BaseTimeEntity.createdAt을 그대로 쓴다.
 * order -> account -> member 경로로 조인하면 사용자별 체결 로그 조회에도 그대로 쓸 수 있다.
 */
@Entity
@Table(name = "execution")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Execution extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(nullable = false)
    private long quantity;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    @Builder
    private Execution(Order order, long quantity, BigDecimal price) {
        this.order = order;
        this.quantity = quantity;
        this.price = price;
    }
}
