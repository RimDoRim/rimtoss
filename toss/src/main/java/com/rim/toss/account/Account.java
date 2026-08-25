package com.rim.toss.account;

import java.math.BigDecimal;

import com.rim.toss.common.BaseTimeEntity;
import com.rim.toss.member.Member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자 1명당 1개의 가상 계좌(현금 잔고). 보유 종목은 {@link Holding}에서 관리한다.
 */
@Entity
@Table(name = "account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false, unique = true)
    private Member member;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal cashBalance;

    @Builder
    private Account(Member member, BigDecimal cashBalance) {
        this.member = member;
        this.cashBalance = cashBalance;
    }

    public void deposit(BigDecimal amount) {
        this.cashBalance = this.cashBalance.add(amount);
    }

    public void withdraw(BigDecimal amount) {
        if (this.cashBalance.compareTo(amount) < 0) {
            throw new IllegalStateException("주문 가능 현금이 부족합니다.");
        }
        this.cashBalance = this.cashBalance.subtract(amount);
    }
}
