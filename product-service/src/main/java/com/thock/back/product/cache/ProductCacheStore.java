package com.thock.back.product.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductCacheStore {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${product.cache.enabled:false}")
    private boolean cacheEnabled;

    @Value("${product.cache.ttl-seconds:600}")
    private long ttlSeconds;

    public Optional<ProductCacheSnapshot> findById(Long productId) {
        if (!cacheEnabled || productId == null) {
            return Optional.empty();
        }

        String key = key(productId);
        String rawValue = stringRedisTemplate.opsForValue().get(key);

        if (rawValue == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(objectMapper.readValue(rawValue, ProductCacheSnapshot.class));
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize product cache. key={}", key, e);
            stringRedisTemplate.delete(key);
            return Optional.empty();
        }
    }

    public Map<Long, ProductCacheSnapshot> findAllByIds(List<Long> productIds) {
        if (!cacheEnabled || productIds == null || productIds.isEmpty()) {
            return Map.of();
        }

        List<String> keys = productIds.stream()
                .map(this::key)
                .toList();

        List<String> rawValues = stringRedisTemplate.opsForValue().multiGet(keys);
        if (rawValues == null || rawValues.isEmpty()) {
            return Map.of();
        }

        Map<Long, ProductCacheSnapshot> result = new HashMap<>();

        for (int i = 0; i < productIds.size(); i++) {
            String rawValue = rawValues.get(i);
            if (rawValue == null) {
                continue;
            }

            Long productId = productIds.get(i);
            String key = keys.get(i);

            try {
                ProductCacheSnapshot snapshot = objectMapper.readValue(rawValue, ProductCacheSnapshot.class);
                result.put(productId, snapshot);
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize product cache. key={}", key, e);
                stringRedisTemplate.delete(key);
            }
        }

        return result;
    }

    public void save(ProductCacheSnapshot snapshot) {
        if (!cacheEnabled || snapshot == null || snapshot.id() == null) {
            return;
        }

        try {
            String key = key(snapshot.id());
            String payload = objectMapper.writeValueAsString(snapshot);

            stringRedisTemplate.opsForValue()
                    .set(key, payload, Duration.ofSeconds(ttlSeconds));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize product cache. productId={}", snapshot.id(), e);
        }
    }

    public void saveAll(List<ProductCacheSnapshot> snapshots) {
        if (!cacheEnabled || snapshots == null || snapshots.isEmpty()) {
            return;
        }

        for (ProductCacheSnapshot snapshot : snapshots) {
            save(snapshot);
        }
    }

    public void evict(Long productId) {
        if (!cacheEnabled || productId == null) {
            return;
        }

        stringRedisTemplate.delete(key(productId));
    }

    private String  key(Long productId) {
        return "product:detail:" + productId;
    }
}
