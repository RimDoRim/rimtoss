package com.rim.toss.member;

import com.rim.toss.common.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 친한 사람들끼리만 쓰는 폐쇄형 서비스라 별도 비밀번호 없이 이름+전화번호 조합으로 로그인한다.
 * 가입 신청은 {@link MemberStatus#PENDING}으로 시작하고, 관리자가 승인해야 로그인이 가능해진다.
 */
@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String name;

    @Column(nullable = false, unique = true, length = 20)
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberStatus status;

    @Builder
    private Member(String name, String phoneNumber, MemberRole role, MemberStatus status) {
        this.name = name;
        this.phoneNumber = phoneNumber;
        this.role = role;
        this.status = status;
    }

    public void approve() {
        if (this.status == MemberStatus.APPROVED) {
            throw new IllegalStateException("이미 승인된 회원입니다.");
        }
        this.status = MemberStatus.APPROVED;
    }

    public void reject() {
        if (this.status == MemberStatus.APPROVED) {
            throw new IllegalStateException("이미 승인된 회원은 거절할 수 없습니다.");
        }
        this.status = MemberStatus.REJECTED;
    }
}
