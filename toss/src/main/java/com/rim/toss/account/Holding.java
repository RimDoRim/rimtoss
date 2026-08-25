package com.rim.toss.account;

import java.math.BigDecimal;
import java.math.RoundingMode;

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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 계좌가 보유한 종목 1건. market+symbol 조합마다 하나만 존재한다.
 * market/symbol 표기는 toss-collector, distribution-server가 쓰는
 * "kr" / "005930" 형식을 그대로 따른다.
 */
@Entity
@Table(name = "holding", uniqueConstraints = @UniqueConstraint(columnNames = {"account_id", "market", "symbol"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Holding extends BaseTimeEntity {

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

    @Column(nullable = false)
    private long quantity;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal avgPrice;

    @Builder
    private Holding(Account account, String market, String symbol, long quantity, BigDecimal avgPrice) {
        this.account = account;
        this.market = market;
        this.symbol = symbol;
        this.quantity = quantity;
        this.avgPrice = avgPrice;
    }

    /** 추가 매수 체결분을 반영하고 평단가를 재계산한다. */
    public void applyBuy(long buyQuantity, BigDecimal buyPrice) {
        BigDecimal existingCost = this.avgPrice.multiply(BigDecimal.valueOf(this.quantity));
        BigDecimal addedCost = buyPrice.multiply(BigDecimal.valueOf(buyQuantity));
        long newQuantity = this.quantity + buyQuantity;

        this.avgPrice = existingCost.add(addedCost)
                .divide(BigDecimal.valueOf(newQuantity), 4, RoundingMode.HALF_UP);
        this.quantity = newQuantity;
    }

    /** 매도 체결분만큼 수량을 줄인다. 평단가는 매도로 변하지 않는다. */
    public void applySell(long sellQuantity) {
        if (this.quantity < sellQuantity) {
            throw new IllegalStateException("보유 수량이 부족합니다.");
        }
        this.quantity -= sellQuantity;
    }
}
