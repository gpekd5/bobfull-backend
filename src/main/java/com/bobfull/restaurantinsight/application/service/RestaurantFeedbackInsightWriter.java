package com.bobfull.restaurantinsight.application.service;

import com.bobfull.restaurantinsight.domain.entity.RestaurantFeedbackInsight;
import com.bobfull.restaurantinsight.infrastructure.repository.RestaurantFeedbackInsightRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 리뷰 지적(MAJOR) 재검증: 저장을 별도 {@code REQUIRES_NEW} 트랜잭션으로 분리해, 동시 중복 처리 경쟁에서
 * 발생하는 UNIQUE 제약 위반이 호출자의(있다면) 외부 트랜잭션을 rollback-only로 오염시키지 않게 한다.
 * 호출자는 이 메서드가 던지는 {@link org.springframework.dao.DataIntegrityViolationException}을 잡아
 * 정상 중복 경쟁으로 처리하면 되고, 그 예외는 이 메서드 전용의 이미 rollback된 트랜잭션에서만 발생한다.
 */
@Component
class RestaurantFeedbackInsightWriter {
    private final RestaurantFeedbackInsightRepository insights;

    RestaurantFeedbackInsightWriter(RestaurantFeedbackInsightRepository insights) {
        this.insights = insights;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void save(RestaurantFeedbackInsight insight) {
        insights.saveAndFlush(insight);
    }
}
