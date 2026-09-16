package com.bobfull.restaurantinsight.application.service;

import com.bobfull.restaurantinsight.domain.entity.RestaurantFeedbackInsight;
import com.bobfull.restaurantinsight.infrastructure.repository.RestaurantFeedbackInsightRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// Insight 저장을 독립 트랜잭션으로 확정해 중복 경쟁을 호출자와 격리한다.
@Service
@RequiredArgsConstructor
class RestaurantFeedbackInsightTransactionService {

    private final RestaurantFeedbackInsightRepository insights;

    // UNIQUE 위반이 호출자 트랜잭션을 rollback-only로 만들지 않도록 별도 트랜잭션에서 즉시 flush한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void save(RestaurantFeedbackInsight insight) {
        insights.saveAndFlush(insight);
    }
}
