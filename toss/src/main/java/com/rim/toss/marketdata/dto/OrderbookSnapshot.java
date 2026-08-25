package com.rim.toss.marketdata.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 토스 WS orderbookStream 메시지의 data 객체와 1:1 대응.
 * (https://openapi.tossinvest.com/openapi-docs/latest/asyncapi.json,
 *  channels.realtime-orderbook.messages.orderbookStream 확인, 2026-08-25)
 *
 * asks는 낮은 가격순(오름차순), bids는 높은 가격순(내림차순)으로 온다 — 그래서 각 리스트의
 * 첫 원소가 최우선호가(best ask/bid)다. timestamp는 데이터 미제공 시 null일 수 있다(required 아님).
 * currency는 스펙에 "클라이언트는 unknown enum 값을 허용하도록 구현해야 합니다"라고 명시돼 있어
 * Java enum으로 강제 파싱하지 않고 String 그대로 둔다.
 */
public record OrderbookSnapshot(
        OffsetDateTime timestamp,
        String currency,
        List<PriceLevel> asks,
        List<PriceLevel> bids
) {
    public Optional<PriceLevel> bestAsk() {
        return asks == null || asks.isEmpty() ? Optional.empty() : Optional.of(asks.get(0));
    }

    public Optional<PriceLevel> bestBid() {
        return bids == null || bids.isEmpty() ? Optional.empty() : Optional.of(bids.get(0));
    }
}
