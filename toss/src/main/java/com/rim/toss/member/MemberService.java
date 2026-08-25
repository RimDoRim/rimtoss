package com.rim.toss.member;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rim.toss.account.Account;
import com.rim.toss.account.AccountRepository;
import com.rim.toss.member.dto.SignupRequest;

import lombok.RequiredArgsConstructor;

/**
 * 별도 비밀번호 없이 이름+전화번호로만 인증하는 폐쇄형(친구 전용) 서비스.
 * 가입 신청은 PENDING으로 시작하고, 관리자가 승인해야 로그인 및 가상 계좌 개설이 이뤄진다.
 */
@Service
@RequiredArgsConstructor
public class MemberService {

    // 가상 투자 사이트이므로 승인 시 시드머니를 지급한다.
    private static final BigDecimal SEED_CASH = new BigDecimal("10000000");

    private final MemberRepository memberRepository;
    private final AccountRepository accountRepository;

    @Transactional
    public Long signup(SignupRequest request) {
        validate(request);
        String phoneNumber = normalizePhoneNumber(request.phoneNumber());
        if (memberRepository.existsByPhoneNumber(phoneNumber)) {
            throw new IllegalArgumentException("이미 가입 신청된 전화번호입니다: " + phoneNumber);
        }

        Member member = Member.builder()
                .name(request.name())
                .phoneNumber(phoneNumber)
                .role(MemberRole.USER)
                .status(MemberStatus.PENDING)
                .build();
        memberRepository.save(member);

        return member.getId();
    }

    /** 이름+전화번호가 모두 일치하고 승인된 회원일 때만 인증 성공. */
    public Member authenticate(String name, String phoneNumber) {
        Member member = memberRepository.findByPhoneNumber(normalizePhoneNumber(phoneNumber))
                .filter(m -> m.getName().equals(name))
                .orElseThrow(() -> new IllegalArgumentException("이름 또는 전화번호가 일치하지 않습니다."));

        if (member.getStatus() == MemberStatus.PENDING) {
            throw new IllegalStateException("아직 관리자 승인 대기 중입니다.");
        }
        if (member.getStatus() == MemberStatus.REJECTED) {
            throw new IllegalStateException("가입이 거절된 계정입니다.");
        }
        return member;
    }

    public Member getByPhoneNumber(String phoneNumber) {
        return memberRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 회원입니다: " + phoneNumber));
    }

    public List<Member> getPendingMembers() {
        return memberRepository.findByStatus(MemberStatus.PENDING);
    }

    /** 승인과 동시에 시드머니가 들어간 가상 계좌를 개설한다. */
    @Transactional
    public void approve(Long memberId) {
        Member member = getById(memberId);
        member.approve();
        if (accountRepository.findByMemberId(member.getId()).isEmpty()) {
            accountRepository.save(Account.builder()
                    .member(member)
                    .cashBalance(SEED_CASH)
                    .build());
        }
    }

    @Transactional
    public void reject(Long memberId) {
        getById(memberId).reject();
    }

    private Member getById(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 회원입니다: " + memberId));
    }

    private void validate(SignupRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("이름은 필수입니다.");
        }
        if (request.phoneNumber() == null || normalizePhoneNumber(request.phoneNumber()).isBlank()) {
            throw new IllegalArgumentException("전화번호는 필수입니다.");
        }
    }

    /** "010-1234-5678"과 "01012345678"을 같은 값으로 취급하기 위해 숫자만 남긴다. */
    static String normalizePhoneNumber(String phoneNumber) {
        return phoneNumber == null ? "" : phoneNumber.replaceAll("[^0-9]", "");
    }
}
