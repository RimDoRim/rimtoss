package com.rim.toss.account;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByMemberId(Long memberId);

    Optional<Account> findByMemberPhoneNumber(String phoneNumber);
}
