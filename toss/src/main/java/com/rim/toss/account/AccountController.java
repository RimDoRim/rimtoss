package com.rim.toss.account;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rim.toss.account.dto.AccountResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountRepository accountRepository;
    private final HoldingRepository holdingRepository;

    @GetMapping("/me")
    public ResponseEntity<AccountResponse> me(@AuthenticationPrincipal UserDetails userDetails) {
        Account account = accountRepository.findByMemberPhoneNumber(userDetails.getUsername())
                .orElseThrow(() -> new IllegalStateException("계좌를 찾을 수 없습니다."));
        List<Holding> holdings = holdingRepository.findByAccountId(account.getId());
        return ResponseEntity.ok(AccountResponse.of(account, holdings));
    }
}
