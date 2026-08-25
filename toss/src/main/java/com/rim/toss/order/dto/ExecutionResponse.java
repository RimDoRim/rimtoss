package com.rim.toss.order.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.rim.toss.order.Execution;

public record ExecutionResponse(Long id, long quantity, BigDecimal price, LocalDateTime executedAt) {

    public static ExecutionResponse from(Execution execution) {
        return new ExecutionResponse(execution.getId(), execution.getQuantity(), execution.getPrice(), execution.getCreatedAt());
    }
}
