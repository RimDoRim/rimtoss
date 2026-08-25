package com.rim.toss.order;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rim.toss.order.dto.ExecutionResponse;
import com.rim.toss.order.dto.OrderResponse;
import com.rim.toss.order.dto.PlaceOrderRequest;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<Long> placeOrder(@AuthenticationPrincipal UserDetails userDetails,
                                            @RequestBody PlaceOrderRequest request) {
        Long orderId = orderService.placeOrder(userDetails.getUsername(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(orderId);
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<Void> cancelOrder(@AuthenticationPrincipal UserDetails userDetails,
                                             @PathVariable Long orderId) {
        orderService.cancelOrder(userDetails.getUsername(), orderId);
        return ResponseEntity.ok().build();
    }

    @GetMapping
    public ResponseEntity<List<OrderResponse>> myOrders(@AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(orderService.getMyOrders(userDetails.getUsername()));
    }

    @GetMapping("/{orderId}/executions")
    public ResponseEntity<List<ExecutionResponse>> executions(@AuthenticationPrincipal UserDetails userDetails,
                                                                @PathVariable Long orderId) {
        return ResponseEntity.ok(orderService.getExecutions(userDetails.getUsername(), orderId));
    }
}
