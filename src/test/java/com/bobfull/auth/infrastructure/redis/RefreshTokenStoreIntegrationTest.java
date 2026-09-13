package com.bobfull.auth.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 실제 Redis(Docker)로 발급·회전·삭제·TTL 만료를 검증하는 선택적 통합 테스트다.
 * BOBFULL_REDIS_INTEGRATION_TEST=true 일 때만 실행하며, Spring 전체 컨텍스트 없이
 * RefreshTokenStore를 직접 구성해 Redis 연결만으로 검증한다(Issue #125).
 */
@EnabledIfEnvironmentVariable(named = "BOBFULL_REDIS_INTEGRATION_TEST", matches = "true")
class RefreshTokenStoreIntegrationTest {

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
    void 로그인으로_발급한_토큰으로_memberId를_조회한다() {
        RefreshTokenStore store = store(600);

        String refreshToken = store.issue(1001L);

        assertThat(store.rotate(refreshToken)).isPresent()
                .get().extracting(RefreshTokenStore.RotatedToken::memberId).isEqualTo(1001L);
    }

    @Test
    void 재발급하면_기존_토큰은_무효화되고_새_토큰만_유효하다() {
        RefreshTokenStore store = store(600);
        String oldToken = store.issue(1002L);

        RefreshTokenStore.RotatedToken rotated = store.rotate(oldToken).orElseThrow();

        assertThat(store.rotate(oldToken)).isEmpty();
        assertThat(store.rotate(rotated.refreshToken())).isPresent();
    }

    @Test
    void 로그인하면_같은_회원의_기존_토큰은_삭제된다() {
        RefreshTokenStore store = store(600);
        String firstToken = store.issue(1003L);

        store.issue(1003L);

        assertThat(store.rotate(firstToken)).isEmpty();
    }

    @Test
    void 로그아웃하면_해당_회원의_토큰이_삭제되어_재발급이_거부된다() {
        RefreshTokenStore store = store(600);
        String refreshToken = store.issue(1004L);

        store.deleteByMember(1004L);

        assertThat(store.rotate(refreshToken)).isEmpty();
    }

    @Test
    void TTL이_지나면_토큰이_자동으로_사라진다() throws InterruptedException {
        RefreshTokenStore shortLivedStore = store(1);
        String refreshToken = shortLivedStore.issue(1005L);

        Thread.sleep(1500);

        assertThat(store(600).rotate(refreshToken)).isEmpty();
    }

    @Test
    void Redis에_연결할_수_없으면_DataAccessException이_발생한다() {
        LettuceConnectionFactory unreachableFactory = new LettuceConnectionFactory("localhost", 1);
        unreachableFactory.afterPropertiesSet();
        try {
            StringRedisTemplate redisTemplate = new StringRedisTemplate(unreachableFactory);
            redisTemplate.afterPropertiesSet();
            RefreshTokenStore store = new RefreshTokenStore(redisTemplate, 600);

            assertThatThrownBy(() -> store.rotate("any-token"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally {
            unreachableFactory.destroy();
        }
    }

    private RefreshTokenStore store(long expirationSeconds) {
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        return new RefreshTokenStore(redisTemplate, expirationSeconds);
    }
}
