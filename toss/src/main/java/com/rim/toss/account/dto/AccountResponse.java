package com.rim.toss.account.dto;

import java.math.BigDecimal;
import java.util.List;

import com.rim.toss.account.Account;
import com.rim.toss.account.Holding;

public record AccountResponse(BigDecimal cashBalance, List<HoldingResponse> holdings) {

    public static AccountResponse of(Account account, List<Holding> holdings) {
        return new AccountResponse(
                account.getCashBalance(),
                holdings.stream().map(HoldingResponse::from).toList()
        );
    }
}
