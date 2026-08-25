package com.rim.toss.account.dto;

import java.math.BigDecimal;

import com.rim.toss.account.Holding;

public record HoldingResponse(String market, String symbol, long quantity, BigDecimal avgPrice) {

    public static HoldingResponse from(Holding holding) {
        return new HoldingResponse(holding.getMarket(), holding.getSymbol(), holding.getQuantity(), holding.getAvgPrice());
    }
}
