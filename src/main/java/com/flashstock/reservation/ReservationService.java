package com.flashstock.reservation;

import com.flashstock.api.ApiException;
import com.flashstock.config.FlashStockProperties;
import com.flashstock.config.DatabaseClock;
import com.flashstock.idempotency.IdempotencyStore;
import com.flashstock.inventory.InventoryRepository;
import com.flashstock.order.OrderEntity;
import com.flashstock.order.OrderRepository;
import com.flashstock.outbox.OutboxEventStore;
import com.flashstock.product.ProductEntity;
import com.flashstock.product.ProductRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class ReservationService {
    private final ReservationRepository reservations;
    private final ProductRepository products;
    private final InventoryRepository inventory;
    private final IdempotencyStore idempotency;
    private final OrderRepository orders;
    private final OutboxEventStore outbox;
    private final FlashStockProperties properties;
    private final MeterRegistry metrics;
    private final DatabaseClock databaseClock;

    public ReservationService(ReservationRepository reservations, ProductRepository products, InventoryRepository inventory,
                              IdempotencyStore idempotency, OrderRepository orders, OutboxEventStore outbox,
                              FlashStockProperties properties, MeterRegistry metrics, DatabaseClock databaseClock) {
        this.reservations=reservations; this.products=products; this.inventory=inventory; this.idempotency=idempotency;
        this.orders=orders; this.outbox=outbox; this.properties=properties; this.metrics=metrics; this.databaseClock=databaseClock;
    }

    @Transactional
    public ReservationDtos.ReservationResponse create(UUID userId, String key, ReservationDtos.CreateRequest request) {
        String fingerprint = fingerprint(request);
        IdempotencyStore.Record record = idempotency.claimAndLock(userId, key, fingerprint);
        if (!MessageDigest.isEqual(record.fingerprint().getBytes(StandardCharsets.US_ASCII), fingerprint.getBytes(StandardCharsets.US_ASCII))) {
            metrics.counter("flashstock.reservations", "outcome", "idempotency_conflict").increment();
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was already used with a different request");
        }
        if (record.reservationId() != null) {
            metrics.counter("flashstock.reservations", "outcome", "idempotent_replay").increment();
            return ReservationDtos.ReservationResponse.from(reservations.findById(record.reservationId()).orElseThrow());
        }
        ProductEntity product = products.findById(request.productId()).orElseThrow(() -> ApiException.notFound("Product"));
        int changed = reserveInventory(request.productId(), request.quantity());
        if (changed != 1) {
            metrics.counter("flashstock.reservations", "outcome", "insufficient_stock").increment();
            throw ApiException.conflict("INSUFFICIENT_STOCK", "There is not enough available inventory");
        }
        Instant now = databaseClock.now();
        ReservationEntity reservation = reservations.saveAndFlush(new ReservationEntity(UUID.randomUUID(), userId, request.productId(), request.quantity(), now.plus(properties.getReservation().getTtl())));
        idempotency.complete(userId, key, reservation.getId());
        outbox.append(reservation.getId(), "ReservationCreated", new ReservationEvent(reservation.getId(), userId, product.getId(), request.quantity(), reservation.getExpiresAt()));
        countAfterCommit("flashstock.reservations", "created");
        return ReservationDtos.ReservationResponse.from(reservation);
    }

    @Transactional(readOnly = true)
    public ReservationDtos.ReservationResponse get(UUID userId, UUID id) {
        ReservationEntity entity = reservations.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Reservation"));
        return ReservationDtos.ReservationResponse.from(entity);
    }

    @Transactional
    public ReservationDtos.ConfirmResponse confirm(UUID userId, UUID id) {
        ReservationEntity reservation = ownedLocked(userId, id);
        var action = ReservationLifecycleRules.confirmationAction(reservation.getStatus(), reservation.getExpiresAt(), databaseClock.now());
        if (action == ReservationLifecycleRules.ConfirmationAction.RETURN_EXISTING_ORDER) {
            return new ReservationDtos.ConfirmResponse(ReservationDtos.ReservationResponse.from(reservation), ReservationDtos.OrderResponse.from(orders.findByReservationId(id).orElseThrow()));
        }
        if (action == ReservationLifecycleRules.ConfirmationAction.EXPIRE) {
            expireLocked(reservation);
            return new ReservationDtos.ConfirmResponse(ReservationDtos.ReservationResponse.from(reservation), null);
        }
        reservation.setStatus(ReservationStatus.CONFIRMED);
        ProductEntity product = products.findById(reservation.getProductId()).orElseThrow(() -> ApiException.notFound("Product"));
        if (inventory.consumeReservation(reservation.getProductId(), reservation.getQuantity()) != 1) throw new IllegalStateException("Inventory/reservation invariant violated during confirmation");
        OrderEntity order = orders.saveAndFlush(new OrderEntity(UUID.randomUUID(), id, userId, product.getId(), reservation.getQuantity(), product.getPrice()));
        outbox.append(id, "OrderConfirmed", new OrderEvent(order.getId(), id, userId, product.getId(), reservation.getQuantity(), order.getTotalAmount()));
        return new ReservationDtos.ConfirmResponse(ReservationDtos.ReservationResponse.from(reservation), ReservationDtos.OrderResponse.from(order));
    }

    @Transactional
    public ReservationDtos.ReservationResponse cancel(UUID userId, UUID id) {
        ReservationEntity reservation = ownedLocked(userId, id);
        if (ReservationLifecycleRules.cancellationAction(reservation.getStatus()) == ReservationLifecycleRules.CancellationAction.RETURN_TERMINAL_STATE) return ReservationDtos.ReservationResponse.from(reservation);
        reservation.setStatus(ReservationStatus.CANCELLED);
        if (inventory.releaseReservation(reservation.getProductId(), reservation.getQuantity()) != 1) throw new IllegalStateException("Inventory/reservation invariant violated during cancellation");
        outbox.append(id, "ReservationCancelled", new ReservationEvent(id, userId, reservation.getProductId(), reservation.getQuantity(), reservation.getExpiresAt()));
        return ReservationDtos.ReservationResponse.from(reservation);
    }

    ReservationEntity ownedLocked(UUID userId, UUID id) {
        return reservations.findByIdAndUserIdForUpdate(id, userId).orElseThrow(() -> ApiException.notFound("Reservation"));
    }

    void expireLocked(ReservationEntity reservation) {
        reservation.setStatus(ReservationStatus.EXPIRED);
        if (inventory.releaseReservation(reservation.getProductId(), reservation.getQuantity()) != 1) throw new IllegalStateException("Inventory/reservation invariant violated during expiration");
        outbox.append(reservation.getId(), "ReservationExpired", new ReservationEvent(reservation.getId(), reservation.getUserId(), reservation.getProductId(), reservation.getQuantity(), reservation.getExpiresAt()));
    }

    private String fingerprint(ReservationDtos.CreateRequest request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((request.productId() + ":" + request.quantity()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }

    private int reserveInventory(UUID productId, long quantity) {
        return switch (properties.getInventory().getReservationStrategy()) {
            case "conditional" -> inventory.reserve(productId, quantity);
            case "row-lock" -> {
                var stock = inventory.findByProductIdForUpdate(productId);
                if (stock.isEmpty() || stock.get().getAvailableQuantity() < quantity) yield 0;
                yield inventory.reserve(productId, quantity);
            }
            default -> throw new IllegalStateException("Unsupported flashstock.inventory.reservation-strategy");
        };
    }

    private void countAfterCommit(String name, String outcome) {
        Runnable increment = () -> metrics.counter(name, "outcome", outcome).increment();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { increment.run(); }
            });
        } else increment.run();
    }
    public record ReservationEvent(UUID reservationId, UUID userId, UUID productId, int quantity, Instant expiresAt) { }
    public record OrderEvent(UUID orderId, UUID reservationId, UUID userId, UUID productId, int quantity, java.math.BigDecimal totalAmount) { }
}
