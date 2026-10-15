package com.flashstock.order;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class OrderEntity {
    @Id private UUID id;
    @Column(name = "reservation_id", nullable = false, unique = true) private UUID reservationId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "product_id", nullable = false) private UUID productId;
    @Column(nullable = false) private int quantity;
    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4) private BigDecimal unitPrice;
    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4) private BigDecimal totalAmount;
    @Column(nullable = false, length = 16) private String status;
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false) private Instant createdAt;
    protected OrderEntity() { }
    public OrderEntity(UUID id, UUID reservationId, UUID userId, UUID productId, int quantity, BigDecimal unitPrice) {
        this.id=id; this.reservationId=reservationId; this.userId=userId; this.productId=productId;
        this.quantity=quantity; this.unitPrice=unitPrice; this.totalAmount=unitPrice.multiply(BigDecimal.valueOf(quantity)); this.status="CONFIRMED";
        this.createdAt=Instant.now();
    }
    public UUID getId() { return id; }
    public UUID getReservationId() { return reservationId; }
    public UUID getUserId() { return userId; }
    public UUID getProductId() { return productId; }
    public int getQuantity() { return quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
