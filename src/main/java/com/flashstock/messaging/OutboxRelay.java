package com.flashstock.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix="flashstock.messaging", name="enabled", havingValue="true", matchIfMissing=true)
public class OutboxRelay {
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;
    public OutboxRelay(JdbcTemplate jdbc, RabbitTemplate rabbit, ObjectMapper mapper, MeterRegistry metrics) {
        this.jdbc=jdbc; this.rabbit=rabbit; this.mapper=mapper; this.metrics=metrics;
    }

    @Scheduled(fixedDelayString="${OUTBOX_POLL_DELAY_MS:500}")
    public void publishDueEvents() {
        List<Event> batch = jdbc.query("SELECT id, aggregate_id, event_type, payload::text FROM outbox_events WHERE published_at IS NULL AND next_attempt_at <= now() ORDER BY created_at, id LIMIT 100",
                (rs, row) -> new Event(rs.getObject("id", UUID.class), rs.getObject("aggregate_id", UUID.class), rs.getString("event_type"), rs.getString("payload")));
        for (Event event : batch) publish(event);
    }

    private void publish(Event event) {
        try {
            JsonNode payload = mapper.readTree(event.payload());
            MessageProperties properties = new MessageProperties();
            properties.setMessageId(event.id().toString());
            properties.setType(event.type());
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setHeader("aggregateId", event.aggregateId().toString());
            Message message = new Message(mapper.writeValueAsBytes(payload), properties);
            CorrelationData correlation = new CorrelationData(event.id().toString());
            rabbit.send(RabbitMessagingConfiguration.EXCHANGE, "lifecycle." + event.type(), message, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) throw new IllegalStateException("Lifecycle event was not routed to a queue");
            jdbc.update("UPDATE outbox_events SET published_at = now(), last_error = NULL WHERE id = ? AND published_at IS NULL", event.id());
            metrics.counter("flashstock.outbox.publish", "outcome", "published").increment();
        } catch (Exception e) {
            jdbc.update("UPDATE outbox_events SET attempts = attempts + 1, next_attempt_at = now() + make_interval(secs => LEAST(300, power(2, LEAST(attempts, 8)))), last_error = 'Broker publish failed' WHERE id = ? AND published_at IS NULL", event.id());
            metrics.counter("flashstock.outbox.publish", "outcome", "failed").increment();
        }
    }
    private record Event(UUID id, UUID aggregateId, String type, String payload) { }
}
