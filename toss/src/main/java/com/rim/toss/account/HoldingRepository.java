package com.rim.toss.account;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HoldingRepository extends JpaRepository<Holding, Long> {

    List<Holding> findByAccountId(Long accountId);

    Optional<Holding> findByAccountIdAndMarketAndSymbol(Long accountId, String market, String symbol);
}
