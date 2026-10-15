package com.flashstock.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class RedisCacheIT {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Test void cacheHitAvoidsTheLoaderAndRedisFailureFallsBackToSource() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.setTimeout(500);
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        var metrics = new SimpleMeterRegistry();
        var cache = new ProductMetadataCache(template, new ObjectMapper().registerModule(new JavaTimeModule()), metrics);
        UUID productId = UUID.randomUUID();
        AtomicInteger loads = new AtomicInteger();
        var entity = new ProductEntity(productId, "Cached item", "CACHE-1", new BigDecimal("2.50"));

        assertThat(cache.get(productId, () -> { loads.incrementAndGet(); return entity; }).name()).isEqualTo("Cached item");
        assertThat(cache.get(productId, () -> { loads.incrementAndGet(); return entity; }).sku()).isEqualTo("CACHE-1");
        assertThat(loads).hasValue(1);

        REDIS.stop();
        UUID fallbackId = UUID.randomUUID();
        var fallback = cache.get(fallbackId, () -> new ProductEntity(fallbackId, "Fallback", "FALLBACK-1", BigDecimal.ONE));
        assertThat(fallback.name()).isEqualTo("Fallback");
        assertThat(metrics.get("flashstock.cache.operations").tag("outcome", "failure").counter().count()).isGreaterThan(0);
        factory.destroy();
        metrics.close();
    }
}
