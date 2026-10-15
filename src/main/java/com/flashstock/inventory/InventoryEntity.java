package com.flashstock.inventory;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inventory")
public class InventoryEntity {
    @Id @Column(name = "product_id") private UUID productId;
    @Column(name = "total_quantity", nullable = false) private long totalQuantity;
    @Column(name = "reserved_quantity", nullable = false) private long reservedQuantity;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected InventoryEntity() { }
    public InventoryEntity(UUID productId, long totalQuantity) {
        this.productId = productId; this.totalQuantity = totalQuantity; this.reservedQuantity = 0; this.updatedAt = Instant.now();
    }
    public UUID getProductId() { return productId; }
    public long getTotalQuantity() { return totalQuantity; }
    public long getReservedQuantity() { return reservedQuantity; }
    public long getAvailableQuantity() { return totalQuantity - reservedQuantity; }
}
