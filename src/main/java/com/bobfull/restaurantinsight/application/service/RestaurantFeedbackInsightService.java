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

/** ChatMessage에서 식당을 역추적해 파생 결과를 만들고 OWNER용 익명 집계를 제공한다. */
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

    @Transactional
    public void analyze(Long messageId) {
        if (insights.findByMessageIdAndPromptVersion(messageId, activePromptVersion).isPresent()) {
            return;
        }

        ChatMessage message = messages.findById(messageId)
                .orElseThrow(() -> new CustomException(ChatErrorCode.CHAT_MESSAGE_ID_NOT_FOUND));
        Long restaurantId = resolveRestaurantId(message.getChatRoomId());
        if (privacyValidator.containsSensitiveIdentifier(message.getContent())) {
            saveExcluded(messageId, restaurantId, RestaurantFeedbackAnalysisStatus.EXCLUDED_INPUT_PII);
            return;
        }
        if (!candidateGate.isCandidate(message.getContent())) {
            saveExcluded(messageId, restaurantId, RestaurantFeedbackAnalysisStatus.EXCLUDED_CANDIDATE);
            return;
        }

        RestaurantFeedbackInsightPort activeProvider = provider.getIfAvailable();
        if (activeProvider == null) {
            // consumer-enabled=false인 Production 기본값에서는 Consumer Bean 자체가 없어 이 메서드가 호출되지
            // 않는다. 반대로 consumer-enabled=true인데 ai.restaurant-insight.enabled=false로 Provider가 없는
            // 설정 오류 상태에서 여기까지 도달하면, 조용히 offset을 커밋시키지 않고 기술 실패로 재시도/DLT되게 한다.
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
        // LLM이 반환한 normalizedAspect는 저장 여부와 무관하게 항상 privacy 검증을 거친다(PII/재식별
        // 단서가 섞인 Item은 그 자체로 신뢰할 수 없는 결과로 보고 버린다). 저장 시에는 MENU aspectType과
        // ETC opinionType만 실제 대상 식별이 필요해(ETC는 "기타"라는 이름과 달리 의미가 enum만으로
        // 확정되지 않는 자유 범주) 검증된 LLM normalizedAspect를 그대로 쓰고, 나머지 opinionType(예:
        // FRIENDLINESS/PRICE_LEVEL/CLEANLINESS 등 의미가 opinionType만으로 이미 확정되는 경우)은
        // 아래에서 canonical 문구로 치환한다.
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
            // aspectType==ETC는 이름과 달리 enum만으로 실제 대상을 특정할 수 없는 자유-target
            // 범주이므로 MENU와 동일하게 취급한다. 그렇지 않으면 opinionType만으로
            // canonicalize할 때 서로 다른 대상("국물"/"반찬"/"소스" 등)이 같은 opinionType
            // 하나로 잘못 병합될 수 있다(리뷰 지적: MAJOR).
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
