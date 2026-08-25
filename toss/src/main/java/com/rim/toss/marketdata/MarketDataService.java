package com.rim.toss.marketdata;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.rim.toss.marketdata.dto.OrderbookSnapshot;
import com.rim.toss.marketdata.dto.TradeSnapshot;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * distribution-server/toss-collector가 Redis에 쓰는 최신 시세 스냅샷을 읽기만 하는 어댑터.
 * 키 규칙: latest:{trade|orderbook}:{market}:{symbol} -> JSON 문자열 (toss-collector.md 참고).
 * 값은 토스 WS message 프레임의 data 객체 그대로다(중계 과정에서 가공되지 않음). 필드 스키마는
 * 공식 AsyncAPI 스펙에서 확인(https://openapi.tossinvest.com/openapi-docs/latest/asyncapi.json,
 * channels.realtime-trade / channels.realtime-orderbook, 2026-08-25) — {@link TradeSnapshot},
 * {@link OrderbookSnapshot} 참고.
 */
@Service
@RequiredArgsConstructor
public class MarketDataService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public Optional<TradeSnapshot> getLatestTrade(String market, String symbol) {
        return getLatest("trade:%s:%s".formatted(market, symbol), TradeSnapshot.class);
    }

    public Optional<OrderbookSnapshot> getLatestOrderbook(String market, String symbol) {
        return getLatest("orderbook:%s:%s".formatted(market, symbol), OrderbookSnapshot.class);
    }

    /** 체결 로직이 쓰는 단일 가격 값(최근 체결가). */
    public Optional<BigDecimal> getLatestPrice(String market, String symbol) {
        return getLatestTrade(market, symbol).map(TradeSnapshot::price);
    }

    private <T> Optional<T> getLatest(String topic, Class<T> type) {
        String raw = redisTemplate.opsForValue().get("latest:" + topic);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(raw, type));
        } catch (JacksonException e) {
            throw new IllegalStateException("마켓데이터 파싱 실패: " + topic, e);
        }
    }
}
