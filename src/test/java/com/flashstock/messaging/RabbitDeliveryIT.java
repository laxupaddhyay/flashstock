package com.flashstock.messaging;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.flashstock.outbox.OutboxEventStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RabbitDeliveryIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("flashstock.security.dev-password", () -> "integration-test-password");
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("flashstock.messaging.enabled", () -> "true");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired RabbitTemplate rabbit;
    @Autowired OutboxEventStore outbox;
    @Autowired OutboxRelay relay;

    @Test
    @Order(1)
    void outboxPublishesAndDuplicateDeliveryIsRecordedOnlyOnce() {
        UUID aggregateId = UUID.randomUUID();
        UUID eventId = outbox.append(aggregateId, "ReservationCreated", new EventPayload(aggregateId, "ReservationCreated"));
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("select published_at is not null from outbox_events where id = ?", Boolean.class, eventId)).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from processed_events where event_id = ?", Integer.class, eventId)).isEqualTo(1);
        });

        MessageProperties properties = new MessageProperties();
        properties.setMessageId(eventId.toString());
        properties.setType("ReservationCreated");
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        Message duplicate = new Message(("{\"reservationId\":\"" + aggregateId + "\"}").getBytes(StandardCharsets.UTF_8), properties);
        rabbit.send(RabbitMessagingConfiguration.EXCHANGE, "lifecycle.ReservationCreated", duplicate);
        rabbit.send(RabbitMessagingConfiguration.EXCHANGE, "lifecycle.ReservationCreated", duplicate);

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select count(*) from processed_events where event_id = ?", Integer.class, eventId)).isEqualTo(1));
    }

    @Test
    @Order(2)
    void unavailableRabbitLeavesOutboxEventPendingForRetry() {
        UUID aggregateId = UUID.randomUUID();
        UUID eventId = outbox.append(aggregateId, "ReservationCreated", new EventPayload(aggregateId, "ReservationCreated"));
        jdbc.update("update outbox_events set next_attempt_at = now() + interval '1 hour' where id = ?", eventId);
        RABBIT.stop();
        jdbc.update("update outbox_events set next_attempt_at = now() - interval '1 second' where id = ?", eventId);
        relay.publishDueEvents();
        assertThat(jdbc.queryForObject("select published_at from outbox_events where id = ?", java.sql.Timestamp.class, eventId)).isNull();
        assertThat(jdbc.queryForObject("select attempts from outbox_events where id = ?", Integer.class, eventId)).isGreaterThan(0);
    }

    private record EventPayload(UUID reservationId, String type) { }
}
