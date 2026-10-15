package com.flashstock.product;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class ProductMetadataCache {
    private static final Logger log = LoggerFactory.getLogger(ProductMetadataCache.class);
    private static final Duration TTL = Duration.ofMinutes(5);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;
    private final AtomicBoolean failureLogged = new AtomicBoolean();
    public ProductMetadataCache(StringRedisTemplate redis, ObjectMapper mapper, MeterRegistry metrics) { this.redis=redis; this.mapper=mapper; this.metrics=metrics; }

    public Metadata get(UUID id, Supplier<ProductEntity> loader) {
        String key = "flashstock:product:" + id;
        try {
            String json = redis.opsForValue().get(key);
            failureLogged.set(false);
            if (json != null) return mapper.readValue(json, Metadata.class);
        } catch (DataAccessException | JsonProcessingException e) {
            cacheFailure("read");
        }
        ProductEntity entity = loader.get();
        Metadata metadata = new Metadata(entity.getId(), entity.getName(), entity.getSku(), entity.getPrice(), entity.getCreatedAt());
        try { redis.opsForValue().set(key, mapper.writeValueAsString(metadata), TTL); }
        catch (DataAccessException | JsonProcessingException e) { cacheFailure("write"); }
        return metadata;
    }

    private void cacheFailure(String operation) {
        metrics.counter("flashstock.cache.operations", "outcome", "failure", "operation", operation).increment();
        if (failureLogged.compareAndSet(false, true)) log.warn("Redis product cache is unavailable; PostgreSQL fallback is active");
    }

    public record Metadata(UUID id, String name, String sku, BigDecimal price, Instant createdAt) { }
}
