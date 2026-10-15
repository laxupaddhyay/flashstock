package com.flashstock.order;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {
    Optional<OrderEntity> findByReservationId(UUID reservationId);
    Optional<OrderEntity> findByIdAndUserId(UUID id, UUID userId);
}
