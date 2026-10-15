package com.flashstock;

import com.flashstock.inventory.InventoryRepository;
import com.flashstock.product.ProductDtos;
import com.flashstock.product.ProductService;
import com.flashstock.reservation.ReservationDtos;
import com.flashstock.reservation.ReservationService;
import com.flashstock.reservation.ReservationStatus;
import com.flashstock.expiration.ExpirationWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ReservationLifecycleIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.username", () -> "test-user");
        registry.add("spring.rabbitmq.password", () -> "test-password");
        registry.add("flashstock.security.dev-password", () -> "integration-test-password");
        registry.add("flashstock.messaging.enabled", () -> "false");
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
    }
    @Autowired ProductService products;
    @Autowired ReservationService reservations;
    @Autowired InventoryRepository inventory;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExpirationWorker expirationWorker;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void repeatedCancellationReleasesStockExactlyOnce() {
        var product = newProduct(3);
        UUID customer = UUID.randomUUID();
        var reservation = reservations.create(customer, "cancel-key", new ReservationDtos.CreateRequest(product.id(), 2));
        reservations.cancel(customer, reservation.reservationId());
        reservations.cancel(customer, reservation.reservationId());
        var persisted = inventory.findById(product.id()).orElseThrow();
        assertThat(persisted.getReservedQuantity()).isZero();
        assertThat(persisted.getTotalQuantity()).isEqualTo(3);
        assertThat(reservations.get(customer, reservation.reservationId()).status()).isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    void repeatedConfirmationReturnsOneOrderAndConsumesStockOnce() {
        var product = newProduct(3);
        UUID customer = UUID.randomUUID();
        var reservation = reservations.create(customer, "confirm-key", new ReservationDtos.CreateRequest(product.id(), 2));
        var first = reservations.confirm(customer, reservation.reservationId());
        var second = reservations.confirm(customer, reservation.reservationId());
        assertThat(first.order().id()).isEqualTo(second.order().id());
        assertThat(jdbc.queryForObject("select count(*) from orders where reservation_id = ?", Integer.class, reservation.reservationId())).isEqualTo(1);
        var persisted = inventory.findById(product.id()).orElseThrow();
        assertThat(persisted.getTotalQuantity()).isEqualTo(1);
        assertThat(persisted.getReservedQuantity()).isZero();
    }

    @Test
    void expiredReservationReleasesStockAndCannotBeConfirmed() {
        var product = newProduct(3);
        UUID customer = UUID.randomUUID();
        var reservation = reservations.create(customer, "expire-key", new ReservationDtos.CreateRequest(product.id(), 2));
        jdbc.update("update reservations set expires_at = now() - interval '1 second' where id = ?", reservation.reservationId());
        var result = reservations.confirm(customer, reservation.reservationId());
        assertThat(result.order()).isNull();
        assertThat(result.reservation().status()).isEqualTo(ReservationStatus.EXPIRED);
        reservations.cancel(customer, reservation.reservationId());
        assertThat(inventory.findById(product.id()).orElseThrow().getReservedQuantity()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from orders where reservation_id = ?", Integer.class, reservation.reservationId())).isZero();
    }

    @Test
    void expirationWorkerCanRestartAndRepeatWithoutReleasingTwice() {
        var product = newProduct(3);
        UUID customer = UUID.randomUUID();
        var reservation = reservations.create(customer, "worker-key", new ReservationDtos.CreateRequest(product.id(), 2));
        jdbc.update("update reservations set expires_at = now() - interval '1 second' where id = ?", reservation.reservationId());
        expirationWorker.expireBatch();
        expirationWorker.expireBatch();
        assertThat(reservations.get(customer, reservation.reservationId()).status()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(inventory.findById(product.id()).orElseThrow().getReservedQuantity()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ? and event_type = 'ReservationExpired'", Integer.class, reservation.reservationId())).isEqualTo(1);
    }

    @Test
    void confirmationRacingExpirationHasOnlyOneTerminalEffect() throws Exception {
        var product = newProduct(1);
        UUID customer = UUID.randomUUID();
        var reservation = reservations.create(customer, "race-key", new ReservationDtos.CreateRequest(product.id(), 1));
        jdbc.update("update reservations set expires_at = now() - interval '1 second' where id = ?", reservation.reservationId());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> confirmation = pool.submit(() -> {
                ready.countDown(); await(start);
                try { reservations.confirm(customer, reservation.reservationId()); }
                catch (com.flashstock.api.ApiException e) {
                    if (!"RESERVATION_NOT_CONFIRMABLE".equals(e.getCode())) throw e;
                }
            });
            Future<?> expiration = pool.submit(() -> { ready.countDown(); await(start); expirationWorker.expireBatch(); });
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue(); start.countDown();
            confirmation.get(30, TimeUnit.SECONDS); expiration.get(30, TimeUnit.SECONDS);
        }
        assertThat(reservations.get(customer, reservation.reservationId()).status()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(inventory.findById(product.id()).orElseThrow().getReservedQuantity()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from orders where reservation_id = ?", Integer.class, reservation.reservationId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ? and event_type = 'ReservationExpired'", Integer.class, reservation.reservationId())).isEqualTo(1);
    }

    @Test
    void transactionRollbackRestoresAtomicInventoryUpdate() {
        var product = newProduct(4);
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            inventory.reserve(product.id(), 2);
            throw new IllegalStateException("simulated failure after stock update");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(inventory.findById(product.id()).orElseThrow().getReservedQuantity()).isZero();
    }

    @Test
    void postgresCheckConstraintsRejectReservedQuantityAboveTotal() {
        UUID productId = UUID.randomUUID();
        jdbc.update("insert into products(id, name, sku, price) values (?, 'Constraint item', ?, 1.00)", productId, "CONSTRAINT-" + productId);
        assertThatThrownBy(() -> jdbc.update("insert into inventory(product_id, total_quantity, reserved_quantity) values (?, 0, 1)", productId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void sameIdempotencyKeyWithDifferentRequestIsRejected() {
        var product = newProduct(5);
        UUID customer = UUID.randomUUID();
        reservations.create(customer, "reused", new ReservationDtos.CreateRequest(product.id(), 1));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> reservations.create(customer, "reused", new ReservationDtos.CreateRequest(product.id(), 2)))
                .isInstanceOf(com.flashstock.api.ApiException.class).hasMessageContaining("different request");
        assertThat(inventory.findById(product.id()).orElseThrow().getReservedQuantity()).isEqualTo(1);
    }

    private ProductDtos.ProductResponse newProduct(long quantity) {
        return products.create(new ProductDtos.CreateRequest("Lifecycle item", "SKU-" + UUID.randomUUID(), new BigDecimal("2.00"), quantity));
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Race-test barrier timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
