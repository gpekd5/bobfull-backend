package com.bobfull.auth.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 실제 Redis(Docker)로 등록·조회·TTL 만료·장애 전파를 검증하는 선택적 통합 테스트다.
 * BOBFULL_REDIS_INTEGRATION_TEST=true 일 때만 실행하며, Spring 전체 컨텍스트 없이
 * AccessTokenBlacklistStore를 직접 구성해 Redis 연결만으로 검증한다(Issue #186).
 */
@EnabledIfEnvironmentVariable(named = "BOBFULL_REDIS_INTEGRATION_TEST", matches = "true")
class AccessTokenBlacklistStoreIntegrationTest {

    private static LettuceConnectionFactory connectionFactory;

    @BeforeAll
    static void connect() {
        String host = System.getenv().getOrDefault("BOBFULL_TEST_REDIS_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("BOBFULL_TEST_REDIS_PORT", "6379"));
        connectionFactory = new LettuceConnectionFactory(host, port);
        connectionFactory.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @Test
    void 등록한_jti는_Blacklist에_있다고_조회된다() {
        AccessTokenBlacklistStore store = store();

        store.blacklist("jti-2001", Duration.ofMinutes(10));

        assertThat(store.isBlacklisted("jti-2001")).isTrue();
    }

    @Test
    void 등록하지_않은_jti는_Blacklist에_없다고_조회된다() {
        AccessTokenBlacklistStore store = store();

        assertThat(store.isBlacklisted("jti-미등록")).isFalse();
    }

    @Test
    void ttl이_0_이하면_등록을_건너뛴다() {
        AccessTokenBlacklistStore store = store();

        store.blacklist("jti-2002", Duration.ZERO);
        store.blacklist("jti-2003", Duration.ofSeconds(-1));

        assertThat(store.isBlacklisted("jti-2002")).isFalse();
        assertThat(store.isBlacklisted("jti-2003")).isFalse();
    }

    @Test
    void TTL이_지나면_Blacklist_등록이_자동으로_사라진다() throws InterruptedException {
        AccessTokenBlacklistStore store = store();
        store.blacklist("jti-2004", Duration.ofSeconds(1));

        Thread.sleep(1500);

        assertThat(store.isBlacklisted("jti-2004")).isFalse();
    }

    @Test
    void Redis에_연결할_수_없으면_DataAccessException이_발생한다() {
        LettuceConnectionFactory unreachableFactory = new LettuceConnectionFactory("localhost", 1);
        unreachableFactory.afterPropertiesSet();
        try {
            StringRedisTemplate redisTemplate = new StringRedisTemplate(unreachableFactory);
            redisTemplate.afterPropertiesSet();
            AccessTokenBlacklistStore store = new AccessTokenBlacklistStore(redisTemplate);

            assertThatThrownBy(() -> store.isBlacklisted("jti-무관"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally {
            unreachableFactory.destroy();
        }
    }

    private AccessTokenBlacklistStore store() {
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        return new AccessTokenBlacklistStore(redisTemplate);
    }
}
