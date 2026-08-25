package com.rim.toss.marketdata.dto;

import java.math.BigDecimal;

/** 호가 1단계(가격+잔량). orderbookStream data.asks[]/bids[] 항목과 1:1 대응. */
public record PriceLevel(BigDecimal price, BigDecimal volume) {
}
