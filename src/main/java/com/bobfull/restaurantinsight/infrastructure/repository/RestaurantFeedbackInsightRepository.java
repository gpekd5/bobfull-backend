package com.bobfull.restaurantinsight.infrastructure.repository;

import com.bobfull.restaurantinsight.domain.entity.RestaurantFeedbackInsight;
import com.bobfull.restaurantinsight.domain.entity.FeedbackAspectType;
import com.bobfull.restaurantinsight.domain.entity.FeedbackCategory;
import com.bobfull.restaurantinsight.domain.entity.FeedbackOpinionType;
import com.bobfull.restaurantinsight.domain.entity.FeedbackSentiment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RestaurantFeedbackInsightRepository extends JpaRepository<RestaurantFeedbackInsight, Long> {

    Optional<RestaurantFeedbackInsight> findByMessageIdAndPromptVersion(Long messageId, String promptVersion);

    // 소수 의견에서 개인을 추정하지 못하도록 최소 발신자 수를 충족한 집계만 OWNER에게 제공한다.
    @Query("""
            select i.category as category, i.aspectType as aspectType, i.normalizedAspect as aspect, i.opinionType as opinionType, i.sentiment as sentiment,
                   count(distinct m.senderMemberId) as senderCount
            from RestaurantFeedbackItem i join i.analysis a join ChatMessage m on a.messageId = m.id
            where a.restaurantId = :restaurantId and a.promptVersion = :promptVersion and a.analyzedAt >= :from
            group by i.category, i.aspectType, i.normalizedAspect, i.opinionType, i.sentiment
            having count(distinct m.senderMemberId) >= :minimumDistinctSenders
            order by count(distinct m.senderMemberId) desc, i.normalizedAspect asc
            """)
    List<Aggregation> aggregateForOwner(
            @Param("restaurantId") Long restaurantId,
            @Param("promptVersion") String promptVersion,
            @Param("from") Instant from,
            @Param("minimumDistinctSenders") long minimumDistinctSenders
    );

    interface Aggregation {

        FeedbackCategory getCategory();

        FeedbackAspectType getAspectType();

        String getAspect();

        FeedbackOpinionType getOpinionType();

        FeedbackSentiment getSentiment();

        long getSenderCount();
    }
}
