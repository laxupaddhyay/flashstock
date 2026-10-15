package com.flashstock.idempotency;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public class IdempotencyStore {
    private final JdbcTemplate jdbc;
    public IdempotencyStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Record claimAndLock(UUID userId, String key, String fingerprint) {
        jdbc.update("INSERT INTO idempotency_records(user_id, idempotency_key, request_fingerprint) VALUES (?, ?, ?) ON CONFLICT (user_id, idempotency_key) DO NOTHING", userId, key, fingerprint);
        return jdbc.queryForObject("SELECT request_fingerprint, reservation_id FROM idempotency_records WHERE user_id = ? AND idempotency_key = ? FOR UPDATE",
                (rs, row) -> new Record(rs.getString(1), rs.getObject(2, UUID.class)), userId, key);
    }
    public void complete(UUID userId, String key, UUID reservationId) {
        int updated = jdbc.update("UPDATE idempotency_records SET reservation_id = ? WHERE user_id = ? AND idempotency_key = ? AND reservation_id IS NULL", reservationId, userId, key);
        if (updated != 1) throw new IllegalStateException("Idempotency result was already completed unexpectedly");
    }
    public record Record(String fingerprint, UUID reservationId) { }
}
