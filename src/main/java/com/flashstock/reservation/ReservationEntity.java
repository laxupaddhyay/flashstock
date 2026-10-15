package com.flashstock.reservation;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reservations")
public class ReservationEntity {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "product_id", nullable = false) private UUID productId;
    @Column(nullable = false) private int quantity;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private ReservationStatus status;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false, insertable = false) private Instant updatedAt;
    protected ReservationEntity() { }
    public ReservationEntity(UUID id, UUID userId, UUID productId, int quantity, Instant expiresAt) {
        this.id = id; this.userId = userId; this.productId = productId; this.quantity = quantity;
        this.expiresAt = expiresAt; this.status = ReservationStatus.PENDING; this.createdAt = Instant.now(); this.updatedAt = this.createdAt;
    }
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getProductId() { return productId; }
    public int getQuantity() { return quantity; }
    public ReservationStatus getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setStatus(ReservationStatus status) { this.status = status; }
    @PreUpdate protected void touch() { this.updatedAt = Instant.now(); }
}
