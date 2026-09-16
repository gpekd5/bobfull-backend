package com.bobfull.restaurantinsight.domain.entity;

import com.bobfull.common.entity.BaseTimeEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 원문을 OWNER에게 노출하지 않는 메시지 단위 식당 피드백 파생 결과다. */
@Entity
@Table(
        name = "restaurant_feedback_analysis",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_feedback_analysis_message_prompt",
                columnNames = {"chat_message_id", "prompt_version"}
        ),
        indexes = @Index(
                name = "idx_feedback_analysis_restaurant_prompt",
                columnList = "restaurant_id,prompt_version,analyzed_at"
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RestaurantFeedbackInsight extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "restaurant_feedback_analysis_id")
    @Getter(AccessLevel.NONE)
    private Long id;

    @Column(name = "chat_message_id", nullable = false)
    private Long messageId;

    @Column(name = "restaurant_id", nullable = false)
    @Getter(AccessLevel.NONE)
    private Long restaurantId;

    @Column(name = "prompt_version", nullable = false, length = 64)
    private String promptVersion;

    @Column(nullable = false, length = 32)
    @Getter(AccessLevel.NONE)
    private String provider;

    @Column(name = "model_name", nullable = false, length = 128)
    @Getter(AccessLevel.NONE)
    private String modelName;

    @Column(name = "analyzed_at", nullable = false)
    @Getter(AccessLevel.NONE)
    private Instant analyzedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RestaurantFeedbackAnalysisStatus status;

    @OneToMany(mappedBy = "analysis", cascade = CascadeType.ALL, orphanRemoval = true)
    @Getter(AccessLevel.NONE)
    private final List<RestaurantFeedbackItem> items = new ArrayList<>();

    private RestaurantFeedbackInsight(
            Long messageId,
            Long restaurantId,
            String promptVersion,
            String provider,
            String modelName,
            Instant analyzedAt,
            RestaurantFeedbackAnalysisStatus status
    ) {
        this.messageId = messageId;
        this.restaurantId = restaurantId;
        this.promptVersion = promptVersion;
        this.provider = provider;
        this.modelName = modelName;
        this.analyzedAt = analyzedAt;
        this.status = status;
    }

    public static RestaurantFeedbackInsight completed(
            Long messageId,
            Long restaurantId,
            String promptVersion,
            String provider,
            String modelName,
            Instant analyzedAt
    ) {
        return new RestaurantFeedbackInsight(
                messageId,
                restaurantId,
                promptVersion,
                provider,
                modelName,
                analyzedAt,
                RestaurantFeedbackAnalysisStatus.COMPLETED
        );
    }

    public static RestaurantFeedbackInsight excluded(
            Long messageId,
            Long restaurantId,
            String promptVersion,
            Instant analyzedAt,
            RestaurantFeedbackAnalysisStatus status
    ) {
        return new RestaurantFeedbackInsight(
                messageId,
                restaurantId,
                promptVersion,
                "BOBFULL_RULE",
                "normal-exclude",
                analyzedAt,
                status
        );
    }

    public void addItem(
            FeedbackCategory category,
            FeedbackAspectType aspectType,
            String normalizedAspect,
            FeedbackOpinionType opinionType,
            FeedbackSentiment sentiment
    ) {
        items.add(RestaurantFeedbackItem.create(
                this,
                category,
                aspectType,
                normalizedAspect,
                opinionType,
                sentiment
        ));
    }
}
