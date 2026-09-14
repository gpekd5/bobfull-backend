package com.bobfull.restaurantinsight.domain.entity;

import com.bobfull.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 원문을 OWNER에게 노출하지 않는 메시지 단위 식당 피드백 파생 결과다. */
@Entity
@Table(name = "restaurant_feedback_analysis", uniqueConstraints = @UniqueConstraint(name = "uk_feedback_analysis_message_prompt", columnNames = {"chat_message_id", "prompt_version"}), indexes = @Index(name = "idx_feedback_analysis_restaurant_prompt", columnList = "restaurant_id,prompt_version,analyzed_at"))
public class RestaurantFeedbackInsight extends BaseTimeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "restaurant_feedback_analysis_id")
    private Long id;
    @Column(name = "chat_message_id", nullable = false) private Long messageId;
    @Column(name = "restaurant_id", nullable = false) private Long restaurantId;
    @Column(name = "prompt_version", nullable = false, length = 64) private String promptVersion;
    @Column(nullable = false, length = 32) private String provider;
    @Column(name = "model_name", nullable = false, length = 128) private String modelName;
    @Column(name = "analyzed_at", nullable = false) private Instant analyzedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private RestaurantFeedbackAnalysisStatus status;
    @OneToMany(mappedBy = "analysis", cascade = CascadeType.ALL, orphanRemoval = true) private final List<RestaurantFeedbackItem> items = new ArrayList<>();
    protected RestaurantFeedbackInsight() { }
    private RestaurantFeedbackInsight(Long messageId, Long restaurantId, String promptVersion, String provider, String modelName, Instant analyzedAt, RestaurantFeedbackAnalysisStatus status) {
        this.messageId=messageId; this.restaurantId=restaurantId; this.promptVersion=promptVersion; this.provider=provider; this.modelName=modelName; this.analyzedAt=analyzedAt; this.status=status;
    }
    public static RestaurantFeedbackInsight completed(Long messageId, Long restaurantId, String promptVersion, String provider, String modelName, Instant analyzedAt) { return new RestaurantFeedbackInsight(messageId, restaurantId, promptVersion, provider, modelName, analyzedAt, RestaurantFeedbackAnalysisStatus.COMPLETED); }
    public static RestaurantFeedbackInsight excluded(Long messageId, Long restaurantId, String promptVersion, Instant analyzedAt, RestaurantFeedbackAnalysisStatus status) { return new RestaurantFeedbackInsight(messageId, restaurantId, promptVersion, "BOBFULL_RULE", "normal-exclude", analyzedAt, status); }
    public void addItem(FeedbackCategory category, FeedbackAspectType aspectType, String normalizedAspect, FeedbackOpinionType opinionType, FeedbackSentiment sentiment) { items.add(RestaurantFeedbackItem.create(this, category, aspectType, normalizedAspect, opinionType, sentiment)); }
    public Long getMessageId() { return messageId; }
    public String getPromptVersion() { return promptVersion; }
    public RestaurantFeedbackAnalysisStatus getStatus() { return status; }
}
