package com.bobfull.restaurant.restaurant.infrastructure.cache;

import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 식당 검색 결과를 Redis에 캐시한다(Issue #62). 인증 Redis(RefreshTokenStore,
 * AccessTokenBlacklistStore)와 같은 Redis 인프라를 재사용하되 key prefix(`bobfull:search:`)를
 * 분리해 책임을 나눈다(Human 결정 Q3).
 *
 * <p>무효화는 개별 key 삭제 대신 버전 번호(namespace 방식)로 처리한다. 검색 결과 하나가 어떤
 * Restaurant 변경에 영향을 받는지 역추적할 수 없으므로(해시된 key), Restaurant 생성·수정·삭제
 * 시 {@link #bumpVersion()}으로 전체 검색 캐시를 한 번에 무효화한다(Issue "TTL과 무효화 계약").
 * 이전 버전 key는 명시적으로 지우지 않고 TTL 만료로 자연 소멸한다.
 *
 * <p><b>한 번의 조회는 하나의 버전 스냅샷만 사용한다(PR #202 재리뷰 반영).</b> {@link #find}가
 * 반환하는 {@link Lookup}에는 그 순간 조회한 버전이 함께 담기고, {@link #put}은 그 버전을
 * 그대로 넘겨받아 다시 읽지 않는다. {@code find}와 {@code put} 사이에 {@code bumpVersion()}이
 * 끼어들어도(DB 조회 중 다른 트랜잭션이 커밋되는 경우) 이번 결과는 조회 시점의 옛 버전 key에만
 * 저장되고, 그 버전은 더 이상 "현재" 버전이 아니므로 이후 어떤 조회도 이 값을 다시 찾지 않는다
 * — TTL이 지나면 조회되지 않은 채로 자연 소멸한다. {@code find}·{@code put}이 각자
 * {@code currentVersion()}을 독립적으로 다시 읽으면, 그 사이 버전이 올라간 경우 DB에서 읽은
 * 옛 값을 "현재" 버전 key에 다시 써버려 TTL 동안 stale 값이 Hit되는 경쟁이 있었다.
 *
 * <p>Redis 장애 시 캐시 조회·저장·버전 증가 모두 예외를 삼키고 로그만 남긴다(Human 결정 Q2
 * Fail-open) — 검색 캐시 장애가 검색 API 자체를 막지 않는다. 로그인·토큰 등 인증 Redis 실패
 * 정책과는 무관하게 독립적으로 동작한다.</p>
 */
@Component
@Slf4j
public class RestaurantSearchCacheStore {

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

    /**
     * 현재 버전 스냅샷을 함께 반환한다. Miss일 때 이 스냅샷을 {@link #put}에 그대로 넘겨야
     * find/put이 서로 다른 버전을 쓰는 경쟁을 피할 수 있다.
     */
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

    /**
     * {@code version}은 반드시 같은 조회에서 {@link #find}가 반환한 {@link Lookup#version()}을
     * 그대로 넘겨야 한다 — 여기서 현재 버전을 다시 읽지 않는다(경쟁 방지, 위 클래스 설명 참고).
     */
    public void put(long version, RestaurantSearchCacheKey key, CachedRestaurantSearchResult result) {
        try {
            String value = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(resultKey(version, key), value, ttl);
        } catch (RuntimeException e) {
            log.warn("event=RESTAURANT_SEARCH_CACHE_WRITE_FAILED reason={}", e.getClass().getSimpleName(), e);
        }
    }

    /**
     * Restaurant 생성·수정·삭제 시 호출해 이후 조회부터 새 버전 key를 쓰게 만든다. 기존에
     * 캐시된 검색 결과는 더 이상 조회되지 않고 TTL이 지나면 자연 삭제된다.
     */
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
