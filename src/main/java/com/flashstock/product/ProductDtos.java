package com.flashstock.product;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class ProductDtos {
    private ProductDtos() { }
    public record CreateRequest(@NotBlank @Size(max=200) String name, @NotBlank @Size(max=80) String sku,
                                @NotNull @DecimalMin("0.00") @Digits(integer=15, fraction=4) BigDecimal price,
                                @NotNull @PositiveOrZero Long initialQuantity) { }
    public record InventoryChange(@NotNull Direction direction, @NotNull @Positive Long quantity) { }
    public enum Direction { INCREASE, DECREASE }
    public record ProductResponse(UUID id, String name, String sku, BigDecimal price, long totalQuantity,
                                  long reservedQuantity, long availableQuantity, Instant createdAt) { }
}
