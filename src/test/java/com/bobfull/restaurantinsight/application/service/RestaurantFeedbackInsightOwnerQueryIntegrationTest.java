package com.bobfull.restaurantinsight.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.chat.domain.entity.ChatMessage;
import com.bobfull.chat.infrastructure.repository.ChatMessageRepository;
import com.bobfull.common.exception.CustomException;
import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;
import com.bobfull.restaurant.restaurant.infrastructure.repository.RestaurantRepository;
import com.bobfull.restaurantinsight.domain.entity.FeedbackAspectType;
import com.bobfull.restaurantinsight.domain.entity.FeedbackCategory;
import com.bobfull.restaurantinsight.domain.entity.FeedbackOpinionType;
import com.bobfull.restaurantinsight.domain.entity.FeedbackSentiment;
import com.bobfull.restaurantinsight.domain.entity.RestaurantFeedbackInsight;
import com.bobfull.restaurantinsight.infrastructure.repository.RestaurantFeedbackInsightRepository;
import com.bobfull.restaurantinsight.presentation.response.RestaurantFeedbackInsightResponse;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

/** #277 PHASE B: OWNER 5-field 집계, distinct sender, 최근 7일, activePromptVersion, 소유권 검증. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:insight-owner;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=test-test-test-test-test-test-test-test",
        "jwt.access-token-expiration-seconds=1800",
        "portone.api-secret=test", "portone.store-id=test", "portone.webhook-secret=dGVzdA==",
        "bobfull.kafka.chat-message.consumer-enabled=false",
        "bobfull.kafka.restaurant-insight.consumer-enabled=false",
        "bobfull.ai.restaurant-insight.enabled=false",
        "bobfull.restaurant-feedback.active-prompt-version=v1"
})
@ContextConfiguration(classes = RestaurantFeedbackInsightServiceIntegrationTest.Config.class)
class RestaurantFeedbackInsightOwnerQueryIntegrationTest {

    @Autowired RestaurantFeedbackInsightService service;
    @Autowired RestaurantRepository restaurants;
    @Autowired ChatMessageRepository messages;
    @Autowired RestaurantFeedbackInsightRepository analyses;

    private static final Long OWNER_ID = 100L;
    private static final Long OTHER_OWNER_ID = 200L;

    @AfterEach
    void clean() {
        analyses.deleteAll();
        messages.deleteAll();
        restaurants.deleteAll();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "activePromptVersion", "v1");
    }

    @Test
    void 동일_sender가_3회_보내도_distinct_1이면_노출되지_않는다() {
        Restaurant restaurant = restaurants.save(Restaurant.create(OWNER_ID, "r", "a", "c", "d", "k", 1));
        for (int i = 0; i < 3; i++) {
            saveAnalysis(restaurant.getId(), sender(1L), "v1",
                    FeedbackCategory.FOOD, FeedbackAspectType.MENU, "탕수육", FeedbackOpinionType.TEXTURE, FeedbackSentiment.POSITIVE, Instant.now());
        }
        var result = service.getOwnerInsights(OWNER_ID, restaurant.getId());
        assertThat(result.insights()).isEmpty();
    }

    @Test
    void 서로_다른_sender_3명이_기여하면_노출되고_count는_distinct_sender_수다() {
        Restaurant restaurant = restaurants.save(Restaurant.create(OWNER_ID, "r", "a", "c", "d", "k", 1));
        saveAnalysis(restaurant.getId(), sender(1L), "v1", FeedbackCategory.FOOD, FeedbackAspectType.MENU, "탕수육", FeedbackOpinionType.TEXTURE, FeedbackSentiment.POSITIVE, Instant.now());
        saveAnalysis(restaurant.getId(), sender(2L), "v1", FeedbackCategory.FOOD, FeedbackAspectType.MENU, "탕수육", FeedbackOpinionType.TEXTURE, FeedbackSentiment.POSITIVE, Instant.now());
        saveAnalysis(restaurant.getId(), sender(3L), "v1", FeedbackCategory.FOOD, FeedbackAspectType.MENU, "탕수육", FeedbackOpinionType.TEXTURE, FeedbackSentiment.POSITIVE, Instant.now());

        var result = service.getOwnerInsights(OWNER_ID, restaurant.getId());
        assertThat(result.insights()).hasSize(1);
        RestaurantFeedbackInsightResponse item = result.insights().get(0);
        assertThat(item.count()).isEqualTo(3);
    }

    @Test
    void 총_8건_메시지여도_distinct_sender_3명이면_count는_3이다() {
        Restaurant restaurant = restaurants.save(Restaurant.create(OWNER_ID, "r", "a", "c", "d", "k", 1));
        long[] senders = {1L, 1L, 1L, 2L, 2L, 2L, 2L, 3L}; // A x3, B x4, C x1 = 8건, distinct 3명
        for (long senderId : senders) {
            saveAnalysis(restaurant.getId(), sender(senderId), "v1", FeedbackCategory.FOOD, FeedbackAspectType.MENU, "탕수육", FeedbackOpinionType.TEXTURE, FeedbackSentiment.POSITIVE, Instant.now());
        }
        var result = service.getOwnerInsights(OWNER_ID, restaurant.getId());
        assertThat(result.insights()).hasSize(1);
        assertThat(result.insights().get(0).count()).isEqualTo(3);
    }

    @Test
    void aspectType만_다르면_다른_그룹으로_집계된다() {
        Restaurant restaurant = restaurants.save(Restaurant.create(OWNER_ID, "r", "a", "c", "d", "k", 1));
        saveAnalysis(restaurant.getId(), sender(1L), "v1", FeedbackCategory.FOOD, FeedbackAspectType.MENU, "청결", FeedbackOpinionType.CLEANLINESS, FeedbackSentiment.POSITIVE, Instant.now());
        saveAnalysis(restaurant.getId(), sender(2L), "v1", FeedbackCategory.FOOD, FeedbackAspectType.MENU, "청결", FeedbackOpinionType.CLEANLINESS, FeedbackSentiment.POSITIVE, Instant.now());
        saveAnalysis(restaurant.getId(), sender(3L), "v1", FeedbackCategory.FOOD, FeedbackAspectType.CLEANLINESS, "청결", FeedbackOpinionType.CLEANLINESS, FeedbackSentiment.POSITIVE, Instant.now());

        var result = service.getOwnerInsights(OWNER_ID, restaurant.getId());
        assertThat(result.insights()).isEmpty(); // 두 그룹 모두 distinct 2명뿐
    }

    @Test
    void opinionType만_다르면_다른_그룹으로_집계된다() {
        Restaurant restaurant = restaurants.save(Restaurant.create(OWNER_ID, "r", "a", "c", "d", "k", 1));
        saveAnalysis(restaurant.getId(), sender(1L), "v1", FeedbackCategory.FOOD, FeedbackAspectType.MENU, "탕수육", FeedbackOpinionType.TEXTURE, FeedbackSentiment.POSITIVE, Instant.now());
        saveAnalysis(restaurant.getId(), sender(2L), "v1", FeedbackCategory.FOOD, FeedbackAspectType.MENU, "탕수육", FeedbackOpinionType.TEXTURE, FeedbackSentiment.POSITIVE, Instant.now());
        saveAnalysis(restaurant.getId(), sender(3L), "v1", FeedbackCategory.FOOD, FeedbackAspectType.MENU, "탕수육", FeedbackOpinionType.TASTE, FeedbackSentiment.POSITIVE, Instant.now());

        var result = service.getOwnerInsights(OWNER_ID, restaurant.getId());
        assertThat(result.insights()).isEmpty();
    }

    @Test
    void sentiment만_다르면_다른_그룹으로_집계된다() {
        Restaurant restaurant = restaurants.save(Restaurant.create(OWNER_ID, "r", "a", "c", "d", "k", 1));
        saveAnalysis(restaurant.getId(), sender(1L), "v1", FeedbackCategory.FOOD, FeedbackAspectType.MENU, "탕수육", FeedbackOpinionType.TEXTURE, FeedbackSentiment.POSITIVE, Instant.now());
        saveAnalysis(restaurant.getId(), sender(2L), "v1", FeedbackCategory.FOOD, FeedbackAspectType.MENU, "탕수육", FeedbackOpinionType.TEXTURE, FeedbackSentiment.POSITIVE, Instant.now());
        saveAnalysis(restaurant.getId(), sender(3L), "v1", FeedbackCategory.FOOD, FeedbackAspectType.MENU, "탕수육", FeedbackOpinionType.TEXTURE, FeedbackSentiment.NEGATIVE, Instant.now());

        var result = service.getOwnerInsights(OWNER_ID, restaurant.getId());
        assertThat(result.insights()).isEmpty();
    }

    @Test
    void 다른_restaurantId의_결과와_섞이지_않는다() {
        Restaurant restaurantA = restaurants.save(Restaurant.create(OWNER_ID, "rA", "a", "c", "d", "k", 1));
        Restaurant restaurantB = restaurants.save(Restaurant.create(OWNER_ID, "rB", "a", "c", "d", "k", 1));
        saveAnalysis(restaurantA.getId(), sender(1L), "v1", FeedbackCategory.FOOD, FeedbackAspectType.MENU, "탕수육", FeedbackOpinionType.TEXTURE, FeedbackSentiment.POSITIVE, Instant.now());
        saveAnalysis(restaurantA.getId(), sender(2L), "v1", FeedbackCategory.FOOD, FeedbackAspectType.MENU, "탕수육", FeedbackOpinionType.TEXTURE, FeedbackSentiment.POSITIVE, Instant.now());
        saveAnalysis(restaurantB.getId(), sender(3L), "v1", FeedbackCategory.FOOD, FeedbackAspectType.MENU, "탕수육", FeedbackOpinionType.TEXTURE, FeedbackSentiment.POSITIVE, Instant.now());

        assertThat(service.getOwnerInsights(OWNER_ID, restaurantA.getId()).insights()).isEmpty();
        assertThat(service.getOwnerInsights(OWNER_ID, restaurantB.getId()).insights()).isEmpty();
    }

    @Test
    void 최근_7일_이내_결과만_포함하고_기간_밖은_제외한다() {
        Restaurant restaurant = restaurants.save(Restaurant.create(OWNER_ID, "r", "a", "c", "d", "k", 1));
        saveAnalysis(restaurant.getId(), sender(1L), "v1", FeedbackCategory.FOOD, FeedbackAspectType.MENU, "탕수육", FeedbackOpinionType.TEXTURE, FeedbackSentiment.POSITIVE, Instant.now());
        saveAnalysis(restaurant.getId(), sender(2L), "v1", FeedbackCategory.FOOD, FeedbackAspectType.MENU, "탕수육", FeedbackOpinionType.TEXTURE, FeedbackSentiment.POSITIVE, Instant.now());
        // 기간 밖(8일 전)의 3번째 sender는 집계에서 제외되어야 하므로 distinct 2명에 그친다.
        saveAnalysis(restaurant.getId(), sender(3L), "v1", FeedbackCategory.FOOD, FeedbackAspectType.MENU, "탕수육", FeedbackOpinionType.TEXTURE, FeedbackSentiment.POSITIVE, Instant.now().minus(Duration.ofDays(8)));

        assertThat(service.getOwnerInsights(OWNER_ID, restaurant.getId()).insights()).isEmpty();

        // 4번째 sender를 기간 안에 추가하면 distinct 3명이 되어 노출된다.
        saveAnalysis(restaurant.getId(), sender(4L), "v1", FeedbackCategory.FOOD, FeedbackAspectType.MENU, "탕수육", FeedbackOpinionType.TEXTURE, FeedbackSentiment.POSITIVE, Instant.now());
        var result = service.getOwnerInsights(OWNER_ID, restaurant.getId());
        assertThat(result.insights()).hasSize(1);
        assertThat(result.insights().get(0).count()).isEqualTo(3);
    }

    @Test
    void v1_v2가_공존해도_activePromptVersion인_v2만_집계한다() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "activePromptVersion", "v2");
        Restaurant restaurant = restaurants.save(Restaurant.create(OWNER_ID, "r", "a", "c", "d", "k", 1));
        // v1 결과는 3명이 기여하지만 activePromptVersion=v2이므로 집계 대상이 아니다.
        saveAnalysis(restaurant.getId(), sender(1L), "v1", FeedbackCategory.FOOD, FeedbackAspectType.MENU, "탕수육", FeedbackOpinionType.TEXTURE, FeedbackSentiment.POSITIVE, Instant.now());
        saveAnalysis(restaurant.getId(), sender(2L), "v1", FeedbackCategory.FOOD, FeedbackAspectType.MENU, "탕수육", FeedbackOpinionType.TEXTURE, FeedbackSentiment.POSITIVE, Instant.now());
        saveAnalysis(restaurant.getId(), sender(3L), "v1", FeedbackCategory.FOOD, FeedbackAspectType.MENU, "탕수육", FeedbackOpinionType.TEXTURE, FeedbackSentiment.POSITIVE, Instant.now());
        assertThat(service.getOwnerInsights(OWNER_ID, restaurant.getId()).insights()).isEmpty();

        saveAnalysis(restaurant.getId(), sender(4L), "v2", FeedbackCategory.FOOD, FeedbackAspectType.MENU, "짜장면", FeedbackOpinionType.SALTINESS, FeedbackSentiment.NEGATIVE, Instant.now());
        saveAnalysis(restaurant.getId(), sender(5L), "v2", FeedbackCategory.FOOD, FeedbackAspectType.MENU, "짜장면", FeedbackOpinionType.SALTINESS, FeedbackSentiment.NEGATIVE, Instant.now());
        saveAnalysis(restaurant.getId(), sender(6L), "v2", FeedbackCategory.FOOD, FeedbackAspectType.MENU, "짜장면", FeedbackOpinionType.SALTINESS, FeedbackSentiment.NEGATIVE, Instant.now());

        var result = service.getOwnerInsights(OWNER_ID, restaurant.getId());
        assertThat(result.insights()).hasSize(1);
        assertThat(result.insights().get(0).normalizedAspect()).isEqualTo("짜장면");
    }

    @Test
    void 본인_소유_식당은_조회에_성공한다() {
        Restaurant restaurant = restaurants.save(Restaurant.create(OWNER_ID, "r", "a", "c", "d", "k", 1));
        var result = service.getOwnerInsights(OWNER_ID, restaurant.getId());
        assertThat(result.restaurantId()).isEqualTo(restaurant.getId());
    }

    @Test
    void 타인_소유_식당_조회는_거부된다() {
        Restaurant restaurant = restaurants.save(Restaurant.create(OWNER_ID, "r", "a", "c", "d", "k", 1));
        assertThatThrownBy(() -> service.getOwnerInsights(OTHER_OWNER_ID, restaurant.getId()))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("접근 권한");
    }

    @Test
    void 존재하지_않는_식당은_기존_ErrorCode_계약대로_거부된다() {
        assertThatThrownBy(() -> service.getOwnerInsights(OWNER_ID, 999_999L))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("찾을 수 없");
    }

    private Long sender(long senderMemberId) {
        return messages.save(ChatMessage.create(1L, senderMemberId, senderMemberId, "탕수육 맛 좋아요")).getId();
    }

    private void saveAnalysis(Long restaurantId, Long messageId, String promptVersion,
            FeedbackCategory category, FeedbackAspectType aspectType, String normalizedAspect,
            FeedbackOpinionType opinionType, FeedbackSentiment sentiment, Instant analyzedAt) {
        RestaurantFeedbackInsight insight = RestaurantFeedbackInsight.completed(messageId, restaurantId, promptVersion, "fake", "fake", analyzedAt);
        insight.addItem(category, aspectType, normalizedAspect, opinionType, sentiment);
        analyses.saveAndFlush(insight);
    }
}
