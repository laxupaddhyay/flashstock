package com.flashstock.expiration;

import com.flashstock.config.FlashStockProperties;
import com.flashstock.config.DatabaseClock;
import com.flashstock.inventory.InventoryRepository;
import com.flashstock.outbox.OutboxEventStore;
import com.flashstock.reservation.ReservationEntity;
import com.flashstock.reservation.ReservationRepository;
import com.flashstock.reservation.ReservationService.ReservationEvent;
import com.flashstock.reservation.ReservationStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.util.List;

@Component
public class ExpirationWorker {
    private final ReservationRepository reservations;
    private final InventoryRepository inventory;
    private final OutboxEventStore outbox;
    private final FlashStockProperties properties;
    private final MeterRegistry metrics;
    private final DatabaseClock databaseClock;

    public ExpirationWorker(ReservationRepository reservations, InventoryRepository inventory, OutboxEventStore outbox,
                            FlashStockProperties properties, MeterRegistry metrics, DatabaseClock databaseClock) {
        this.reservations=reservations; this.inventory=inventory; this.outbox=outbox; this.properties=properties; this.metrics=metrics; this.databaseClock=databaseClock;
    }

    @Scheduled(fixedDelayString = "${EXPIRATION_POLL_DELAY_MS:1000}")
    @Transactional
    public void expireBatch() {
        Instant now = databaseClock.now();
        List<ReservationEntity> expired = reservations.claimExpired(now, properties.getReservation().getExpirationBatchSize());
        int processed = 0;
        for (ReservationEntity reservation : expired) {
            reservation.setStatus(ReservationStatus.EXPIRED);
            if (inventory.releaseReservation(reservation.getProductId(), reservation.getQuantity()) != 1) {
                throw new IllegalStateException("Inventory/reservation invariant violated during expiration");
            }
            outbox.append(reservation.getId(), "ReservationExpired", new ReservationEvent(reservation.getId(), reservation.getUserId(), reservation.getProductId(), reservation.getQuantity(), reservation.getExpiresAt()));
            processed++;
        }
        if (processed > 0) {
            int committedCount = processed;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { metrics.counter("flashstock.expiration.processed").increment(committedCount); }
            });
        }
    }
}
