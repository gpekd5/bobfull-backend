package com.bobfull.restaurantinsight.application.service;

import com.bobfull.chat.domain.entity.ChatMessage;
import com.bobfull.chat.domain.entity.ChatRoom;
import com.bobfull.chat.domain.exception.ChatErrorCode;
import com.bobfull.chat.infrastructure.repository.ChatMessageRepository;
import com.bobfull.chat.infrastructure.repository.ChatRoomRepository;
import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;
import com.bobfull.restaurant.restaurant.domain.exception.RestaurantErrorCode;
import com.bobfull.restaurant.restaurant.infrastructure.repository.RestaurantRepository;
import com.bobfull.restaurant.sharedtable.domain.entity.SharedTable;
import com.bobfull.restaurant.sharedtable.infrastructure.repository.SharedTableRepository;
import com.bobfull.restaurant.timeslot.domain.entity.TimeSlot;
import com.bobfull.restaurant.timeslot.infrastructure.repository.TimeSlotRepository;
import com.bobfull.restaurantinsight.application.model.RestaurantFeedbackAnalysis;
import com.bobfull.restaurantinsight.application.port.RestaurantFeedbackInsightPort;
import com.bobfull.restaurantinsight.application.result.RestaurantFeedbackAnalysisResult;
import com.bobfull.restaurantinsight.domain.entity.FeedbackAspectType;
import com.bobfull.restaurantinsight.domain.entity.FeedbackOpinionType;
import com.bobfull.restaurantinsight.domain.entity.RestaurantFeedbackAnalysisStatus;
import com.bobfull.restaurantinsight.domain.entity.RestaurantFeedbackInsight;
import com.bobfull.restaurantinsight.domain.policy.RestaurantInsightAspectCanonicalizer;
import com.bobfull.restaurantinsight.domain.policy.RestaurantInsightCandidateGate;
import com.bobfull.restaurantinsight.domain.policy.RestaurantInsightPrivacyValidator;
import com.bobfull.restaurantinsight.infrastructure.repository.RestaurantFeedbackInsightRepository;
import com.bobfull.restaurantinsight.presentation.response.RestaurantFeedbackInsightListResponse;
import com.bobfull.restaurantinsight.presentation.response.RestaurantFeedbackInsightResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 채팅 리뷰를 선별·보호·분석해 익명 식당 Insight로 저장하고 OWNER 집계를 제공한다.
@Service
public class RestaurantFeedbackInsightService {

    private static final long MINIMUM_DISTINCT_SENDERS = 3;

    private final ChatMessageRepository messages;
    private final ChatRoomRepository rooms;
    private final ReservationRepository reservations;
    private final TimeSlotRepository timeSlots;
    private final SharedTableRepository tables;
    private final RestaurantRepository restaurants;
    private final RestaurantFeedbackInsightRepository insights;
    private final RestaurantInsightCandidateGate candidateGate;
    private final RestaurantInsightPrivacyValidator privacyValidator;
    private final ObjectProvider<RestaurantFeedbackInsightPort> provider;
    private final Clock clock;
    private final String activePromptVersion;
    private final RestaurantFeedbackInsightTransactionService transactionService;

    public RestaurantFeedbackInsightService(
            ChatMessageRepository messages,
            ChatRoomRepository rooms,
            ReservationRepository reservations,
            TimeSlotRepository timeSlots,
            SharedTableRepository tables,
            RestaurantRepository restaurants,
            RestaurantFeedbackInsightRepository insights,
            RestaurantInsightCandidateGate candidateGate,
            RestaurantInsightPrivacyValidator privacyValidator,
            ObjectProvider<RestaurantFeedbackInsightPort> provider,
            Clock clock,
            @Value("${bobfull.restaurant-feedback.active-prompt-version:restaurant-feedback-v1}")
            String activePromptVersion,
            RestaurantFeedbackInsightTransactionService transactionService
    ) {
        this.messages = messages;
        this.rooms = rooms;
        this.reservations = reservations;
        this.timeSlots = timeSlots;
        this.tables = tables;
        this.restaurants = restaurants;
        this.insights = insights;
        this.candidateGate = candidateGate;
        this.privacyValidator = privacyValidator;
        this.provider = provider;
        this.clock = clock;
        this.activePromptVersion = activePromptVersion;
        this.transactionService = transactionService;
    }

    // 중복 분석을 건너뛰고 개인정보·후보 검증 후 AI 결과를 정규화해 저장한다.
    @Transactional
    public void analyze(Long messageId) {
        if (insights.findByMessageIdAndPromptVersion(messageId, activePromptVersion).isPresent()) {
            return;
        }

        ChatMessage message = messages.findById(messageId)
                .orElseThrow(() -> new CustomException(ChatErrorCode.CHAT_MESSAGE_ID_NOT_FOUND));
        Long restaurantId = resolveRestaurantId(message.getChatRoomId());
        // 리뷰 원문이 외부 AI 신뢰 경계를 넘기 전에 개인정보와 재식별 단서를 먼저 차단한다.
        if (privacyValidator.containsSensitiveIdentifier(message.getContent())) {
            saveExcluded(messageId, restaurantId, RestaurantFeedbackAnalysisStatus.EXCLUDED_INPUT_PII);
            return;
        }
        // Rule은 명백한 비후보만 선제 제외해 불필요한 외부 AI 호출을 줄인다.
        if (!candidateGate.isCandidate(message.getContent())) {
            saveExcluded(messageId, restaurantId, RestaurantFeedbackAnalysisStatus.EXCLUDED_CANDIDATE);
            return;
        }

        RestaurantFeedbackInsightPort activeProvider = provider.getIfAvailable();
        if (activeProvider == null) {
            // Consumer만 활성화된 설정 오류는 offset을 커밋하지 않고 기술 실패로 재시도·DLT 처리한다.
            throw new IllegalStateException(
                    "Restaurant Insight consumer is enabled but no RestaurantFeedbackInsightPort bean is configured"
            );
        }

        RestaurantFeedbackAnalysisResult result = activeProvider.analyze(message.getContent());
        if (result.analysis() == null) {
            throw new IllegalStateException("Insight structured output analysis is missing");
        }

        List<RestaurantFeedbackAnalysis.Item> rawItems =
                !result.analysis().relevant() || result.analysis().items() == null
                        ? List.of()
                        : result.analysis().items();
        // 모델 출력도 신뢰 경계 밖의 값이므로 모든 자유 텍스트를 저장 전에 다시 검증한다.
        List<RestaurantFeedbackAnalysis.Item> validItems = rawItems.stream()
                .filter(item -> item.category() != null
                        && item.aspectType() != null
                        && item.opinionType() != null
                        && item.sentiment() != null
                        && privacyValidator.isSafeAspect(item.normalizedAspect()))
                .toList();
        RestaurantFeedbackInsight insight = validItems.isEmpty()
                ? RestaurantFeedbackInsight.excluded(
                        messageId,
                        restaurantId,
                        activePromptVersion,
                        clock.instant(),
                        RestaurantFeedbackAnalysisStatus.EXCLUDED_OUTPUT_VALIDATION
                )
                : RestaurantFeedbackInsight.completed(
                        messageId,
                        restaurantId,
                        activePromptVersion,
                        result.provider(),
                        result.modelName(),
                        clock.instant()
                );

        // canonical 치환으로 한 메시지 안에서 두 Item이 동일한 5-field로 수렴할 수 있으므로(예: LLM이
        // 같은 opinionType을 두 번 반환), UNIQUE(analysis, 5-field) 위반을 막기 위해 저장 전 중복 제거한다.
        LinkedHashSet<List<Object>> seenKeys = new LinkedHashSet<>();
        for (RestaurantFeedbackAnalysis.Item item : validItems) {
            // MENU와 ETC는 enum만으로 실제 대상을 특정할 수 없어 검증된 모델 문구를 유지한다.
            // 이를 canonicalize하면 "국물"과 "반찬"처럼 서로 다른 대상이 같은 의견으로 병합될 수 있다.
            boolean keepLlmAspect = item.aspectType() == FeedbackAspectType.MENU
                    || item.aspectType() == FeedbackAspectType.ETC
                    || item.opinionType() == FeedbackOpinionType.ETC;
            String normalizedAspect = keepLlmAspect
                    ? privacyValidator.normalizeSafeAspect(item.normalizedAspect())
                    : RestaurantInsightAspectCanonicalizer.canonicalAspectFor(item.opinionType());
            List<Object> key = List.of(
                    item.category(),
                    item.aspectType(),
                    normalizedAspect,
                    item.opinionType(),
                    item.sentiment()
            );
            if (seenKeys.add(key)) {
                insight.addItem(
                        item.category(),
                        item.aspectType(),
                        normalizedAspect,
                        item.opinionType(),
                        item.sentiment()
                );
            }
        }

        // transactionService.save()는 REQUIRES_NEW로 별도 트랜잭션에서 실행되므로, 동시 경쟁으로 인한
        // UNIQUE 위반이 이 메서드(및 호출자인 Kafka listener)의 트랜잭션을 rollback-only로 오염시키지 않는다.
        try {
            transactionService.save(insight);
        } catch (DataIntegrityViolationException exception) {
            if (insights.findByMessageIdAndPromptVersion(messageId, activePromptVersion).isEmpty()) {
                throw exception;
            }
        }
    }

    private void saveExcluded(
            Long messageId,
            Long restaurantId,
            RestaurantFeedbackAnalysisStatus status
    ) {
        try {
            transactionService.save(RestaurantFeedbackInsight.excluded(
                    messageId,
                    restaurantId,
                    activePromptVersion,
                    clock.instant(),
                    status
            ));
        } catch (DataIntegrityViolationException exception) {
            if (insights.findByMessageIdAndPromptVersion(messageId, activePromptVersion).isEmpty()) {
                throw exception;
            }
        }
    }

    // OWNER 권한을 확인하고 최근 7일간 서로 다른 발신자 3명 이상인 익명 의견만 집계한다.
    @Transactional(readOnly = true)
    public RestaurantFeedbackInsightListResponse getOwnerInsights(Long ownerId, Long restaurantId) {
        Restaurant restaurant = restaurants.findByIdAndDeletedAtIsNull(restaurantId)
                .orElseThrow(() -> new CustomException(RestaurantErrorCode.RESTAURANT_ID_NOT_FOUND));
        if (!restaurant.getOwnerMemberId().equals(ownerId)) {
            throw new CustomException(CommonErrorCode.ACCESS_DENIED);
        }

        Instant now = clock.instant();
        Instant from = now.minus(Duration.ofDays(7));
        List<RestaurantFeedbackInsightResponse> responses = insights.aggregateForOwner(
                        restaurantId,
                        activePromptVersion,
                        from,
                        MINIMUM_DISTINCT_SENDERS
                ).stream()
                .map(RestaurantFeedbackInsightResponse::from)
                .toList();
        return new RestaurantFeedbackInsightListResponse(restaurantId, from, now, responses);
    }

    private Long resolveRestaurantId(Long chatRoomId) {
        ChatRoom room = rooms.findById(chatRoomId)
                .orElseThrow(() -> new CustomException(ChatErrorCode.CHAT_ROOM_ID_NOT_FOUND));
        Reservation reservation = reservations.findById(room.getReservationId())
                .orElseThrow(() -> new IllegalStateException("Insight reservation missing"));
        TimeSlot timeSlot = timeSlots.findById(reservation.getTimeSlotId())
                .orElseThrow(() -> new IllegalStateException("Insight timeSlot missing"));
        SharedTable table = tables.findById(timeSlot.getSharedTableId())
                .orElseThrow(() -> new IllegalStateException("Insight sharedTable missing"));
        return restaurants.findById(table.getRestaurantId())
                .orElseThrow(() -> new IllegalStateException("Insight restaurant missing"))
                .getId();
    }
}
