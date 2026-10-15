package com.flashstock.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.UUID;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<InventoryEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InventoryEntity i where i.productId = :productId")
    Optional<InventoryEntity> findByProductIdForUpdate(@Param("productId") UUID productId);

    @Modifying
    @Query(value = "UPDATE inventory SET reserved_quantity = reserved_quantity + :quantity, updated_at = now() WHERE product_id = :productId AND total_quantity - reserved_quantity >= :quantity", nativeQuery = true)
    int reserve(@Param("productId") UUID productId, @Param("quantity") long quantity);

    @Modifying
    @Query(value = "UPDATE inventory SET total_quantity = total_quantity + :quantity, updated_at = now() WHERE product_id = :productId AND total_quantity <= 9223372036854775807 - :quantity", nativeQuery = true)
    int increase(@Param("productId") UUID productId, @Param("quantity") long quantity);

    @Modifying
    @Query(value = "UPDATE inventory SET total_quantity = total_quantity - :quantity, updated_at = now() WHERE product_id = :productId AND total_quantity - reserved_quantity >= :quantity", nativeQuery = true)
    int decrease(@Param("productId") UUID productId, @Param("quantity") long quantity);

    @Modifying
    @Query(value = "UPDATE inventory SET total_quantity = total_quantity - :quantity, reserved_quantity = reserved_quantity - :quantity, updated_at = now() WHERE product_id = :productId AND reserved_quantity >= :quantity", nativeQuery = true)
    int consumeReservation(@Param("productId") UUID productId, @Param("quantity") long quantity);

    @Modifying
    @Query(value = "UPDATE inventory SET reserved_quantity = reserved_quantity - :quantity, updated_at = now() WHERE product_id = :productId AND reserved_quantity >= :quantity", nativeQuery = true)
    int releaseReservation(@Param("productId") UUID productId, @Param("quantity") long quantity);
}
