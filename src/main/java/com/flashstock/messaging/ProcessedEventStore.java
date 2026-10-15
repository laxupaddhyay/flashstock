package com.flashstock.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Repository
public class ProcessedEventStore {
    private final JdbcTemplate jdbc;
    public ProcessedEventStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Transactional
    public boolean markIfNew(String consumer, UUID eventId) {
        return jdbc.update("INSERT INTO processed_events(consumer_name, event_id) VALUES (?, ?) ON CONFLICT DO NOTHING", consumer, eventId) == 1;
    }
}
