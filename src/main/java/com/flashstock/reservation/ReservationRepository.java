package com.flashstock.reservation;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<ReservationEntity, UUID> {
    Optional<ReservationEntity> findByIdAndUserId(UUID id, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReservationEntity r where r.id = :id and r.userId = :userId")
    Optional<ReservationEntity> findByIdAndUserIdForUpdate(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query(value = "SELECT * FROM reservations WHERE status = 'PENDING' AND expires_at <= :now ORDER BY expires_at, id LIMIT :batchSize FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<ReservationEntity> claimExpired(@Param("now") Instant now, @Param("batchSize") int batchSize);
}
