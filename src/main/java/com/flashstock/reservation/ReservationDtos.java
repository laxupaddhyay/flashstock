package com.flashstock.reservation;

import com.flashstock.order.OrderEntity;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class ReservationDtos {
    private ReservationDtos() { }
    public record CreateRequest(@NotNull UUID productId, @NotNull @Positive Integer quantity) { }
    public record ReservationResponse(UUID reservationId, UUID productId, int quantity, ReservationStatus status, Instant expiresAt, Instant createdAt) {
        public static ReservationResponse from(ReservationEntity entity) { return new ReservationResponse(entity.getId(), entity.getProductId(), entity.getQuantity(), entity.getStatus(), entity.getExpiresAt(), entity.getCreatedAt()); }
    }
    public record OrderResponse(UUID id, UUID reservationId, UUID productId, int quantity, BigDecimal unitPrice, BigDecimal totalAmount, String status, Instant createdAt) {
        public static OrderResponse from(OrderEntity entity) { return new OrderResponse(entity.getId(), entity.getReservationId(), entity.getProductId(), entity.getQuantity(), entity.getUnitPrice(), entity.getTotalAmount(), entity.getStatus(), entity.getCreatedAt()); }
    }
    public record ConfirmResponse(ReservationResponse reservation, OrderResponse order) { }
}
