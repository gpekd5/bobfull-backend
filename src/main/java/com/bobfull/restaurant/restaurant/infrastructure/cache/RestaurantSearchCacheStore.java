package com.bobfull.restaurant.restaurant.infrastructure.cache;

import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

// 식당 검색 결과와 무효화 버전을 Redis에서 관리한다.
// 캐시 장애가 검색 API를 막지 않도록 모든 Redis 연산을 fail-open 처리한다.
@Component
@Slf4j
public class RestaurantSearchCacheStore {

    // 검색 key에서 영향받는 식당을 역추적할 수 없어 버전 namespace로 전체 결과를 무효화한다.
    private static final String VERSION_KEY = "bobfull:search:restaurants:version";
    private static final String RESULT_KEY_PREFIX = "bobfull:search:restaurants:v1:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public RestaurantSearchCacheStore(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${restaurant.search-cache.ttl-seconds:60}") long ttlSeconds
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    // 결과와 현재 버전 스냅샷을 함께 반환해 Miss 저장도 같은 namespace를 사용하게 한다.
    public Lookup find(RestaurantSearchCacheKey key) {
        try {
            long version = currentVersion();
            String value = redisTemplate.opsForValue().get(resultKey(version, key));
            if (value == null) {
                return new Lookup(version, Optional.empty());
            }
            return new Lookup(version, Optional.of(objectMapper.readValue(value, CachedRestaurantSearchResult.class)));
        } catch (RuntimeException e) {
            log.warn("event=RESTAURANT_SEARCH_CACHE_READ_FAILED reason={}", e.getClass().getSimpleName(), e);
            // Redis 자체를 못 읽어 버전도 알 수 없다 — 0을 스냅샷으로 반환한다. put()이 이 값을
            // 그대로 써도 Redis가 여전히 불통이라 어차피 no-op으로 실패하므로 안전하다(Fail-open).
            return new Lookup(0L, Optional.empty());
        }
    }

    // 조회에서 받은 버전에 저장해 무효화 중 stale 결과가 새 namespace로 유입되는 경쟁을 막는다.
    public void put(long version, RestaurantSearchCacheKey key, CachedRestaurantSearchResult result) {
        try {
            String value = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(resultKey(version, key), value, ttl);
        } catch (RuntimeException e) {
            log.warn("event=RESTAURANT_SEARCH_CACHE_WRITE_FAILED reason={}", e.getClass().getSimpleName(), e);
        }
    }

    // 이후 검색을 새 namespace로 전환하고 이전 결과는 TTL로 자연 만료시킨다.
    public void bumpVersion() {
        try {
            redisTemplate.opsForValue().increment(VERSION_KEY);
        } catch (RuntimeException e) {
            log.warn("event=RESTAURANT_SEARCH_CACHE_VERSION_BUMP_FAILED reason={}", e.getClass().getSimpleName(), e);
        }
    }

    private long currentVersion() {
        String value = redisTemplate.opsForValue().get(VERSION_KEY);
        return value == null ? 0L : Long.parseLong(value);
    }

    private String resultKey(long version, RestaurantSearchCacheKey key) {
        return RESULT_KEY_PREFIX + version + ":" + key.digest();
    }

    public record Lookup(long version, Optional<CachedRestaurantSearchResult> result) {
    }
}
