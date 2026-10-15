package com.flashstock.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class OutboxBacklogMetrics {
    private final AtomicLong pending = new AtomicLong();
    private final JdbcTemplate jdbc;
    public OutboxBacklogMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        registry.gauge("flashstock.outbox.backlog", pending);
        refresh();
    }
    @Scheduled(fixedDelayString = "${OUTBOX_METRICS_POLL_DELAY_MS:5000}")
    public void refresh() {
        try { pending.set(jdbc.queryForObject("select count(*) from outbox_events where published_at is null", Long.class)); }
        catch (Exception ignored) { }
    }
}
