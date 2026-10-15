package com.flashstock.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix="flashstock.messaging", name="enabled", havingValue="true", matchIfMissing=true)
public class LifecycleEventConsumer {
    private final ProcessedEventStore processed;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;
    public LifecycleEventConsumer(ProcessedEventStore processed, ObjectMapper mapper, MeterRegistry metrics) {
        this.processed=processed; this.mapper=mapper; this.metrics=metrics;
    }

    @RabbitListener(queues=RabbitMessagingConfiguration.LIFECYCLE_QUEUE)
    public void consume(org.springframework.amqp.core.Message message) throws Exception {
        UUID eventId = UUID.fromString(message.getMessageProperties().getMessageId());
        String eventType = message.getMessageProperties().getType();
        JsonNode ignoredPayload = mapper.readTree(message.getBody());
        if (eventType == null || eventType.isBlank() || !ignoredPayload.isObject()) throw new IllegalArgumentException("Malformed lifecycle event");
        boolean firstDelivery = processed.markIfNew("lifecycle-audit", eventId);
        metrics.counter("flashstock.messaging.delivery", "outcome", firstDelivery ? "processed" : "duplicate").increment();
    }
}
