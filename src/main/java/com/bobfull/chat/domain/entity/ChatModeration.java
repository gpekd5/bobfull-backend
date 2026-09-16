package com.bobfull.chat.domain.entity;

import com.bobfull.common.entity.BaseTimeEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 채팅 원문과 분리해 AI 분석 결과·모델 정보·최종 실패를 기록한다.
@Entity
@Table(name = "chat_moderation", uniqueConstraints = @UniqueConstraint(
        name = "uk_chat_moderation_message", columnNames = "chat_message_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatModeration extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chat_moderation_id")
    private Long id;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "chat_message_id", nullable = false, updatable = false)
    private Long messageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ModerationProcessingStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private ModerationResultType result;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "chat_moderation_category",
            joinColumns = @JoinColumn(
                    name = "chat_moderation_id",
                    foreignKey = @ForeignKey(name = "fk_chat_moderation_category_moderation")))
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 32)
    private Set<ModerationCategory> categories = EnumSet.noneOf(ModerationCategory.class);

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", length = 16)
    private RiskLevel riskLevel;

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(name = "model_name", nullable = false, length = 128)
    private String model;

    @Column(name = "prompt_version", nullable = false, length = 64)
    private String promptVersion;

    @Column(name = "policy_version", nullable = false, length = 64)
    private String policyVersion;

    @Column(name = "latency_millis", nullable = false)
    private long latencyMillis;

    @Column(name = "prompt_tokens")
    private Long promptTokens;

    @Column(name = "completion_tokens")
    private Long completionTokens;

    @Column(name = "total_tokens")
    private Long totalTokens;

    @Column(name = "analyzed_at", nullable = false)
    private Instant analyzedAt;

    @Column(name = "error_code", length = 128)
    private String errorCode;

    private ChatModeration(Long messageId, ModerationProcessingStatus status, ModerationResultType result,
            Set<ModerationCategory> categories, RiskLevel riskLevel, String provider, String model,
            String promptVersion, String policyVersion, long latencyMillis, Long promptTokens,
            Long completionTokens, Long totalTokens, Instant analyzedAt, String errorCode) {
        this.messageId = messageId;
        this.status = status;
        this.result = result;
        this.categories = categories.isEmpty() ? EnumSet.noneOf(ModerationCategory.class) : EnumSet.copyOf(categories);
        this.riskLevel = riskLevel;
        this.provider = provider;
        this.model = model;
        this.promptVersion = promptVersion;
        this.policyVersion = policyVersion;
        this.latencyMillis = latencyMillis;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.analyzedAt = analyzedAt;
        this.errorCode = errorCode;
    }
    public static ChatModeration completed(Long messageId, ModerationResultType result, Set<ModerationCategory> categories,
            RiskLevel riskLevel, String provider, String model, String promptVersion, String policyVersion,
            long latencyMillis, Long promptTokens, Long completionTokens, Long totalTokens, Instant analyzedAt) {
        return new ChatModeration(messageId, result == ModerationResultType.SAFE ? ModerationProcessingStatus.SAFE : ModerationProcessingStatus.FLAGGED,
                result, categories, riskLevel, provider, model, promptVersion, policyVersion, latencyMillis,
                promptTokens, completionTokens, totalTokens, analyzedAt, null);
    }
    public static ChatModeration failed(Long messageId, String provider, String model, String promptVersion,
            String policyVersion, long latencyMillis, Instant analyzedAt, String errorCode) {
        return new ChatModeration(messageId, ModerationProcessingStatus.ANALYSIS_FAILED, null,
                Collections.emptySet(), null, provider, model, promptVersion, policyVersion, latencyMillis,
                null, null, null, analyzedAt, errorCode);
    }
    public void complete(ModerationResultType result, Set<ModerationCategory> categories, RiskLevel riskLevel,
            String provider, String model, String promptVersion, String policyVersion, long latencyMillis,
            Long promptTokens, Long completionTokens, Long totalTokens, Instant analyzedAt) {
        this.status = result == ModerationResultType.SAFE
                ? ModerationProcessingStatus.SAFE
                : ModerationProcessingStatus.FLAGGED;
        this.result = result;
        this.categories = categories.isEmpty() ? EnumSet.noneOf(ModerationCategory.class) : EnumSet.copyOf(categories);
        this.riskLevel = riskLevel;
        this.provider = provider;
        this.model = model;
        this.promptVersion = promptVersion;
        this.policyVersion = policyVersion;
        this.latencyMillis = latencyMillis;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.analyzedAt = analyzedAt;
        this.errorCode = null;
    }
    public void fail(String provider, String model, String promptVersion, String policyVersion, long latencyMillis,
            Instant analyzedAt, String errorCode) {
        this.status = ModerationProcessingStatus.ANALYSIS_FAILED;
        this.result = null;
        this.categories = EnumSet.noneOf(ModerationCategory.class);
        this.riskLevel = null;
        this.provider = provider;
        this.model = model;
        this.promptVersion = promptVersion;
        this.policyVersion = policyVersion;
        this.latencyMillis = latencyMillis;
        this.promptTokens = null;
        this.completionTokens = null;
        this.totalTokens = null;
        this.analyzedAt = analyzedAt;
        this.errorCode = errorCode;
    }
    public boolean isCompleted() {
        return status == ModerationProcessingStatus.SAFE || status == ModerationProcessingStatus.FLAGGED;
    }

    public Set<ModerationCategory> getCategories() {
        return Collections.unmodifiableSet(categories);
    }
}
