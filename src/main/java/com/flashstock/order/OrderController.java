package com.flashstock.order;

import com.flashstock.api.ApiException;
import com.flashstock.reservation.ReservationDtos;
import com.flashstock.security.CurrentCustomer;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderRepository orders;
    public OrderController(OrderRepository orders) { this.orders=orders; }
    @GetMapping("/{id}")
    public ReservationDtos.OrderResponse get(@PathVariable UUID id, Authentication auth) {
        OrderEntity order = orders.findByIdAndUserId(id, CurrentCustomer.id(auth)).orElseThrow(() -> ApiException.notFound("Order"));
        return ReservationDtos.OrderResponse.from(order);
    }
}
