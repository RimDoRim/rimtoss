package com.rim.toss.marketdata.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 토스 WS tradeStream 메시지의 data 객체와 1:1 대응 (price/volume/timestamp/currency 모두 required).
 * (https://openapi.tossinvest.com/openapi-docs/latest/asyncapi.json,
 *  channels.realtime-trade.messages.tradeStream 확인, 2026-08-25)
 *
 * currency는 스펙에 "클라이언트는 unknown enum 값을 허용하도록 구현해야 합니다"라고 명시돼 있어
 * Java enum으로 강제 파싱하지 않고 String 그대로 둔다.
 */
public record TradeSnapshot(
        BigDecimal price,
        BigDecimal volume,
        OffsetDateTime timestamp,
        String currency
) {
}
