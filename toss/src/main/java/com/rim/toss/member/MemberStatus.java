package com.rim.toss.member;

/** 가입 신청 상태. 관리자가 승인해야 로그인이 가능해진다. */
public enum MemberStatus {
    PENDING,
    APPROVED,
    REJECTED
}
