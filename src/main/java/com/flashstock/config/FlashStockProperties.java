package com.flashstock.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import jakarta.annotation.PostConstruct;
import java.time.Duration;

@ConfigurationProperties("flashstock")
public class FlashStockProperties {
    private final Reservation reservation = new Reservation();
    private final Inventory inventory = new Inventory();
    private final Security security = new Security();
    private final Messaging messaging = new Messaging();
    public Reservation getReservation() { return reservation; }
    public Inventory getInventory() { return inventory; }
    public Security getSecurity() { return security; }
    public Messaging getMessaging() { return messaging; }
    @PostConstruct
    void validate() {
        if (reservation.ttl == null || reservation.ttl.isZero() || reservation.ttl.isNegative()) throw new IllegalStateException("flashstock.reservation.ttl must be positive");
        if (reservation.expirationBatchSize < 1 || reservation.expirationBatchSize > 10_000) throw new IllegalStateException("flashstock.reservation.expiration-batch-size must be between 1 and 10000");
        if (!inventory.reservationStrategy.equals("conditional") && !inventory.reservationStrategy.equals("row-lock")) throw new IllegalStateException("flashstock.inventory.reservation-strategy must be conditional or row-lock");
    }
    public static class Reservation {
        private Duration ttl = Duration.ofMinutes(10);
        private int expirationBatchSize = 100;
        public Duration getTtl() { return ttl; }
        public void setTtl(Duration ttl) { this.ttl = ttl; }
        public int getExpirationBatchSize() { return expirationBatchSize; }
        public void setExpirationBatchSize(int expirationBatchSize) { this.expirationBatchSize = expirationBatchSize; }
    }
    public static class Inventory {
        private String reservationStrategy = "conditional";
        public String getReservationStrategy() { return reservationStrategy; }
        public void setReservationStrategy(String reservationStrategy) { this.reservationStrategy = reservationStrategy; }
    }
    public static class Security {
        private String devUser = "demo";
        private String devPassword;
        public String getDevUser() { return devUser; }
        public void setDevUser(String devUser) { this.devUser = devUser; }
        public String getDevPassword() { return devPassword; }
        public void setDevPassword(String devPassword) { this.devPassword = devPassword; }
    }
    public static class Messaging {
        private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
