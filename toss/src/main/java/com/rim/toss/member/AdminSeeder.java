package com.rim.toss.member;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.rim.toss.account.Account;
import com.rim.toss.account.AccountRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 관리자 승인 없이는 누구도 가입할 수 없으므로, 최초 관리자 1명은 애플리케이션 시작 시
 * app.admin.* 설정값으로 자동 생성한다(ADMIN 계정이 하나도 없을 때만). 기본값을 그대로 쓰면
 * 아무나 로그인할 수 있으므로 application.yaml/환경변수로 반드시 바꿔서 배포할 것.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminSeeder implements ApplicationRunner {

    private static final BigDecimal SEED_CASH = new BigDecimal("10000000");

    private final MemberRepository memberRepository;
    private final AccountRepository accountRepository;

    @Value("${app.admin.name}")
    private String adminName;

    @Value("${app.admin.phone-number}")
    private String adminPhoneNumber;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (memberRepository.existsByRole(MemberRole.ADMIN)) {
            return;
        }

        String normalizedPhone = MemberService.normalizePhoneNumber(adminPhoneNumber);
        Member admin = Member.builder()
                .name(adminName)
                .phoneNumber(normalizedPhone)
                .role(MemberRole.ADMIN)
                .status(MemberStatus.APPROVED)
                .build();
        memberRepository.save(admin);
        accountRepository.save(Account.builder().member(admin).cashBalance(SEED_CASH).build());

        log.warn("초기 관리자 계정을 생성했습니다: name={}, phoneNumber={} - "
                + "기본값이라면 app.admin.name/app.admin.phone-number를 바꾸고 재배포하세요.",
                adminName, normalizedPhone);
    }
}
