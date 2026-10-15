package com.flashstock.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public class OutboxEventStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public OutboxEventStore(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }
    public UUID append(UUID aggregateId, String eventType, Object payload) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO outbox_events (id, aggregate_id, event_type, payload) VALUES (?, ?, ?, ?::jsonb)", id, aggregateId, eventType, mapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) { throw new IllegalStateException("Unable to serialize event payload", e); }
        return id;
    }
}
