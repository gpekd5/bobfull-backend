package com.bobfull.reservation.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * {@link ReservationCancellationCompletionService#complete}는 결제 완료 트랜잭션의
 * 일부로만 실행돼야 하므로 {@code Propagation.MANDATORY}로 선언돼 있다. 이 테스트는
 * 실제 Spring 트랜잭션 프록시가 그 제약을 강제하는지 검증한다.
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:reservation-cancellation-completion-test;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=create-drop", "jwt.secret=reservation-cancellation-completion-test-secret-key-please-keep-long", "jwt.access-token-expiration-seconds=1800", "portone.api-secret=test", "portone.store-id=test", "portone.webhook-secret=dGVzdA=="})
class ReservationCancellationCompletionServiceIntegrationTest {

    @Autowired
    private ReservationCancellationCompletionService completionService;

    @Test
    void 기존_트랜잭션_없이_단독_호출하면_MANDATORY_전파가_실패한다() {
        assertThatThrownBy(() -> completionService.complete(1L, 1L, Instant.now()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
