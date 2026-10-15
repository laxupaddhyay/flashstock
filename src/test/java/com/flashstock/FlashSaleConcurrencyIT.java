package com.flashstock;

import com.flashstock.api.ApiException;
import com.flashstock.inventory.InventoryRepository;
import com.flashstock.product.ProductDtos;
import com.flashstock.product.ProductService;
import com.flashstock.reservation.ReservationDtos;
import com.flashstock.reservation.ReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class FlashSaleConcurrencyIT {
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

    @Test
    void tenThousandConcurrentReservationsNeverOversell() throws Exception {
        ProductDtos.ProductResponse product = newProduct(100);
        UUID customer = UUID.randomUUID();
        int attempts = 10_000;
        int workers = 48;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        LongAdder accepted = new LongAdder();
        LongAdder rejected = new LongAdder();
        try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
            CompletableFuture<?>[] futures = new CompletableFuture<?>[attempts];
            for (int i = 0; i < attempts; i++) {
                final int attempt = i;
                futures[i] = CompletableFuture.runAsync(() -> {
                    if (attempt < workers) ready.countDown();
                    try {
                        if (!start.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrency barrier timed out");
                        reservations.create(customer, "sale-" + attempt, new ReservationDtos.CreateRequest(product.id(), 1));
                        accepted.increment();
                    } catch (ApiException e) {
                        if (!"INSUFFICIENT_STOCK".equals(e.getCode())) throw e;
                        rejected.increment();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }, pool);
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            CompletableFuture.allOf(futures).get(5, TimeUnit.MINUTES);
        }
        var persisted = inventory.findById(product.id()).orElseThrow();
        assertThat(accepted.sum()).isEqualTo(100);
        assertThat(rejected.sum()).isEqualTo(9_900);
        assertThat(persisted.getTotalQuantity()).isEqualTo(100);
        assertThat(persisted.getReservedQuantity()).isEqualTo(100);
        assertThat(persisted.getAvailableQuantity()).isZero();
    }

    @Test
    void concurrentRetriesWithSameKeyConsumeStockOnce() throws Exception {
        ProductDtos.ProductResponse product = newProduct(10);
        UUID customer = UUID.randomUUID();
        int attempts = 32;
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<UUID> ids = new ConcurrentLinkedQueue<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            CompletableFuture<?>[] futures = new CompletableFuture<?>[attempts];
            for (int i = 0; i < attempts; i++) futures[i] = CompletableFuture.runAsync(() -> {
                ready.countDown();
                try { start.await(); ids.add(reservations.create(customer, "same-key", new ReservationDtos.CreateRequest(product.id(), 1)).reservationId()); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }, pool);
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue(); start.countDown();
            CompletableFuture.allOf(futures).get(2, TimeUnit.MINUTES);
        }
        assertThat(ids).hasSize(attempts).containsOnly(ids.peek());
        assertThat(inventory.findById(product.id()).orElseThrow().getReservedQuantity()).isEqualTo(1);
    }

    @Test
    void concurrentCancellationReleasesStockOnce() throws Exception {
        ProductDtos.ProductResponse product = newProduct(5);
        UUID customer = UUID.randomUUID();
        var reservation = reservations.create(customer, "cancel-race", new ReservationDtos.CreateRequest(product.id(), 3));
        int attempts = 24;
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            CompletableFuture<?>[] futures = new CompletableFuture<?>[attempts];
            for (int i = 0; i < attempts; i++) futures[i] = CompletableFuture.runAsync(() -> {
                ready.countDown();
                try {
                    start.await();
                    assertThat(reservations.cancel(customer, reservation.reservationId()).status().name()).isEqualTo("CANCELLED");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }, pool);
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue(); start.countDown();
            CompletableFuture.allOf(futures).get(2, TimeUnit.MINUTES);
        }
        assertThat(inventory.findById(product.id()).orElseThrow().getReservedQuantity()).isZero();
    }

    @Test
    void concurrentConfirmationCreatesOnlyOnePersistedOrder() throws Exception {
        ProductDtos.ProductResponse product = newProduct(5);
        UUID customer = UUID.randomUUID();
        var reservation = reservations.create(customer, "confirm-race", new ReservationDtos.CreateRequest(product.id(), 2));
        int attempts = 24;
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<UUID> orderIds = new ConcurrentLinkedQueue<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            CompletableFuture<?>[] futures = new CompletableFuture<?>[attempts];
            for (int i = 0; i < attempts; i++) futures[i] = CompletableFuture.runAsync(() -> {
                ready.countDown();
                try { start.await(); orderIds.add(reservations.confirm(customer, reservation.reservationId()).order().id()); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }, pool);
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue(); start.countDown();
            CompletableFuture.allOf(futures).get(2, TimeUnit.MINUTES);
        }
        assertThat(orderIds).hasSize(attempts).containsOnly(orderIds.peek());
    }

    private ProductDtos.ProductResponse newProduct(long quantity) {
        return products.create(new ProductDtos.CreateRequest("Flash item", "SKU-" + UUID.randomUUID(), new BigDecimal("9.9900"), quantity));
    }
}
