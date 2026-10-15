package com.flashstock.product;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "products")
public class ProductEntity {
    @Id private UUID id;
    @Column(nullable = false, length = 200) private String name;
    @Column(nullable = false, length = 80, unique = true) private String sku;
    @Column(nullable = false, precision = 19, scale = 4) private BigDecimal price;
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false) private Instant createdAt;

    protected ProductEntity() { }
    public ProductEntity(UUID id, String name, String sku, BigDecimal price) {
        this.id = id; this.name = name; this.sku = sku; this.price = price; this.createdAt = Instant.now();
    }
    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getSku() { return sku; }
    public BigDecimal getPrice() { return price; }
    public Instant getCreatedAt() { return createdAt; }
}
