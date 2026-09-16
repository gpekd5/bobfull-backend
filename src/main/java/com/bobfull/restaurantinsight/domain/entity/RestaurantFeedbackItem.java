package com.bobfull.restaurantinsight.domain.entity;

import com.bobfull.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 분석된 의견의 집계 기준과 감정 결과를 원문 없이 보관한다.
@Entity
@Table(
        name = "restaurant_feedback_item",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_feedback_item_analysis_key",
                columnNames = {
                        "restaurant_feedback_analysis_id",
                        "category",
                        "aspect_type",
                        "normalized_aspect",
                        "opinion_type",
                        "sentiment"
                }
        ),
        indexes = @Index(
                name = "idx_feedback_item_aggregation",
                columnList = "category,aspect_type,normalized_aspect,opinion_type,sentiment"
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RestaurantFeedbackItem extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "restaurant_feedback_item_id")
    @Getter(AccessLevel.NONE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restaurant_feedback_analysis_id", nullable = false)
    @Getter(AccessLevel.NONE)
    private RestaurantFeedbackInsight analysis;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FeedbackCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "aspect_type", nullable = false, length = 20)
    private FeedbackAspectType aspectType;

    @Column(name = "normalized_aspect", nullable = false, length = 40)
    private String normalizedAspect;

    @Enumerated(EnumType.STRING)
    @Column(name = "opinion_type", nullable = false, length = 20)
    private FeedbackOpinionType opinionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FeedbackSentiment sentiment;

    private RestaurantFeedbackItem(
            RestaurantFeedbackInsight analysis,
            FeedbackCategory category,
            FeedbackAspectType aspectType,
            String normalizedAspect,
            FeedbackOpinionType opinionType,
            FeedbackSentiment sentiment
    ) {
        this.analysis = analysis;
        this.category = category;
        this.aspectType = aspectType;
        this.normalizedAspect = normalizedAspect;
        this.opinionType = opinionType;
        this.sentiment = sentiment;
    }

    static RestaurantFeedbackItem create(
            RestaurantFeedbackInsight analysis,
            FeedbackCategory category,
            FeedbackAspectType aspectType,
            String normalizedAspect,
            FeedbackOpinionType opinionType,
            FeedbackSentiment sentiment
    ) {
        return new RestaurantFeedbackItem(
                analysis,
                category,
                aspectType,
                normalizedAspect,
                opinionType,
                sentiment
        );
    }
}
