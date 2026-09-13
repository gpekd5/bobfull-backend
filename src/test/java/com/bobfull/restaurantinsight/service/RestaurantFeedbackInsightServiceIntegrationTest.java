package com.bobfull.restaurantinsight.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.chat.entity.ChatMessage;
import com.bobfull.chat.entity.ChatRoom;
import com.bobfull.chat.repository.ChatMessageRepository;
import com.bobfull.chat.repository.ChatRoomRepository;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;
import com.bobfull.restaurant.restaurant.infrastructure.repository.RestaurantRepository;
import com.bobfull.restaurantinsight.dto.RestaurantFeedbackAnalysis;
import com.bobfull.restaurantinsight.entity.FeedbackAspectType;
import com.bobfull.restaurantinsight.entity.FeedbackCategory;
import com.bobfull.restaurantinsight.entity.FeedbackOpinionType;
import com.bobfull.restaurantinsight.entity.FeedbackSentiment;
import com.bobfull.restaurantinsight.entity.RestaurantFeedbackAnalysisStatus;
import com.bobfull.restaurantinsight.entity.RestaurantFeedbackInsight;
import com.bobfull.restaurantinsight.port.RestaurantFeedbackInsightPort;
import com.bobfull.restaurantinsight.repository.RestaurantFeedbackInsightRepository;
import com.bobfull.restaurantinsight.repository.RestaurantFeedbackItemRepository;
import com.bobfull.restaurant.sharedtable.domain.entity.SharedTable;
import com.bobfull.restaurant.sharedtable.infrastructure.repository.SharedTableRepository;
import com.bobfull.restaurant.timeslot.domain.entity.TimeSlot;
import com.bobfull.restaurant.timeslot.infrastructure.repository.TimeSlotRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;

/** #277 Service Integration: v1/v2 공존, multi-item, Gate exclude, 순차/동시 멱등성. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:insight;MODE=MySQL;DB_CLOSE_DELAY=-1",
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
class RestaurantFeedbackInsightServiceIntegrationTest {

    @Autowired RestaurantFeedbackInsightService service;
    @Autowired ChatMessageRepository messages;
    @Autowired ChatRoomRepository rooms;
    @Autowired ReservationRepository reservations;
    @Autowired TimeSlotRepository slots;
    @Autowired SharedTableRepository tables;
    @Autowired RestaurantRepository restaurants;
    @Autowired RestaurantFeedbackInsightRepository analyses;
    @Autowired RestaurantFeedbackItemRepository items;
    @Autowired FakeProvider provider;

    @AfterEach
    void clean() {
        items.deleteAll(); analyses.deleteAll(); messages.deleteAll(); rooms.deleteAll();
        reservations.deleteAll(); slots.deleteAll(); tables.deleteAll(); restaurants.deleteAll();
        provider.reset();
        ReflectionTestUtils.setField(service, "activePromptVersion", "v1");
    }

    @Test
    void activePromptVersion_v1으로_분석을_저장한다() {
        Long id = fixture("탕수육 맛 좋아요");
        service.analyze(id);
        assertThat(analyses.findByMessageIdAndPromptVersion(id, "v1")).isPresent();
        assertThat(items.count()).isEqualTo(1);
        assertThat(provider.calls.get()).isEqualTo(1);
    }

    @Test
    void activePromptVersion_v2로_분석을_저장한다() {
        ReflectionTestUtils.setField(service, "activePromptVersion", "v2");
        Long id = fixture("탕수육 맛 좋아요");
        service.analyze(id);
        assertThat(analyses.findByMessageIdAndPromptVersion(id, "v2")).isPresent();
        assertThat(analyses.findByMessageIdAndPromptVersion(id, "v1")).isEmpty();
        assertThat(items.count()).isEqualTo(1);
        assertThat(provider.calls.get()).isEqualTo(1);
    }

    // A1: 동일 messageId에 대해 v1 terminal 결과가 있어도 activePromptVersion=v2 처리를 막지 않고 공존한다.
    @Test
    void v1_v2는_동일_메시지에서_공존하고_서로_수정되지_않는다() {
        Long id = fixture("탕수육 맛 좋아요");
        service.analyze(id); // v1
        assertThat(analyses.findByMessageIdAndPromptVersion(id, "v1")).isPresent();

        ReflectionTestUtils.setField(service, "activePromptVersion", "v2");
        provider.result = List.of(new RestaurantFeedbackAnalysis.Item(FeedbackCategory.FOOD, FeedbackAspectType.MENU, "짜장면", FeedbackOpinionType.SALTINESS, FeedbackSentiment.NEGATIVE));
        service.analyze(id); // v2, v1 short-circuit되면 안 됨

        assertThat(analyses.findByMessageIdAndPromptVersion(id, "v1")).isPresent();
        assertThat(analyses.findByMessageIdAndPromptVersion(id, "v2")).isPresent();
        assertThat(analyses.count()).isEqualTo(2);
        assertThat(provider.calls.get()).isEqualTo(2);
        // v1 결과는 수정되지 않음
        RestaurantFeedbackInsight v1 = analyses.findByMessageIdAndPromptVersion(id, "v1").orElseThrow();
        assertThat(v1.getStatus()).isEqualTo(RestaurantFeedbackAnalysisStatus.COMPLETED);
    }

    // A2: 한 메시지에서 여러 Item이 각각 정확한 5개 필드로 저장된다.
    @Test
    void 한_메시지의_여러_의견을_각각_Item으로_정확한_필드로_저장한다() {
        provider.result = List.of(
                new RestaurantFeedbackAnalysis.Item(FeedbackCategory.FOOD, FeedbackAspectType.MENU, "탕수육", FeedbackOpinionType.TEXTURE, FeedbackSentiment.POSITIVE),
                new RestaurantFeedbackAnalysis.Item(FeedbackCategory.FOOD, FeedbackAspectType.MENU, "짜장면", FeedbackOpinionType.SALTINESS, FeedbackSentiment.NEGATIVE),
                new RestaurantFeedbackAnalysis.Item(FeedbackCategory.SERVICE, FeedbackAspectType.SERVICE, "직원 응대", FeedbackOpinionType.FRIENDLINESS, FeedbackSentiment.POSITIVE)
        );
        service.analyze(fixture("탕수육 짜장면 직원 서비스 후기"));
        assertThat(analyses.count()).isEqualTo(1);
        assertThat(items.count()).isEqualTo(3);
        var all = items.findAll();
        assertThat(all).extracting(com.bobfull.restaurantinsight.entity.RestaurantFeedbackItem::getCategory)
                .containsExactlyInAnyOrder(FeedbackCategory.FOOD, FeedbackCategory.FOOD, FeedbackCategory.SERVICE);
        assertThat(all).extracting(com.bobfull.restaurantinsight.entity.RestaurantFeedbackItem::getAspectType)
                .containsExactlyInAnyOrder(FeedbackAspectType.MENU, FeedbackAspectType.MENU, FeedbackAspectType.SERVICE);
        assertThat(all).extracting(com.bobfull.restaurantinsight.entity.RestaurantFeedbackItem::getNormalizedAspect)
                .containsExactlyInAnyOrder("탕수육", "짜장면", "직원 응대");
        assertThat(all).extracting(com.bobfull.restaurantinsight.entity.RestaurantFeedbackItem::getOpinionType)
                .containsExactlyInAnyOrder(FeedbackOpinionType.TEXTURE, FeedbackOpinionType.SALTINESS, FeedbackOpinionType.FRIENDLINESS);
        assertThat(all).extracting(com.bobfull.restaurantinsight.entity.RestaurantFeedbackItem::getSentiment)
                .containsExactlyInAnyOrder(FeedbackSentiment.POSITIVE, FeedbackSentiment.NEGATIVE, FeedbackSentiment.POSITIVE);
    }

    // 실제 수동 E2E에서 발견된 회귀 재현: LLM이 같은 의견을 메시지마다 다른 문구("친절" vs
    // "직원 친절함")로 normalizedAspect에 담아도, MENU가 아닌 aspectType은 opinionType 기준
    // canonical 문구로 저장되어 항상 같은 5-field 키로 수렴해야 한다(distinct sender 집계가
    // 문구 차이로 쪼개지지 않도록).
    @Test
    void MENU가_아닌_aspectType은_LLM_문구와_무관하게_canonical_normalizedAspect로_수렴한다() {
        provider.result = List.of(new RestaurantFeedbackAnalysis.Item(
                FeedbackCategory.SERVICE, FeedbackAspectType.SERVICE, "직원 친절함", FeedbackOpinionType.FRIENDLINESS, FeedbackSentiment.POSITIVE));
        service.analyze(fixture("직원 친절했어요"));

        provider.result = List.of(new RestaurantFeedbackAnalysis.Item(
                FeedbackCategory.SERVICE, FeedbackAspectType.SERVICE, "친절", FeedbackOpinionType.FRIENDLINESS, FeedbackSentiment.POSITIVE));
        service.analyze(fixture("직원 친절했어요"));

        assertThat(analyses.count()).isEqualTo(2);
        assertThat(items.count()).isEqualTo(2);
        assertThat(items.findAll())
                .extracting(com.bobfull.restaurantinsight.entity.RestaurantFeedbackItem::getNormalizedAspect)
                .containsOnly("직원 응대");
    }

    // ETC opinionType은 "기타"라는 이름과 달리 의미가 enum만으로 확정되지 않는 자유 범주이므로
    // MENU와 마찬가지로 canonicalize하지 않고 검증된 LLM normalizedAspect를 그대로 유지해야 한다.
    @Test
    void ETC_opinionType은_canonicalize하지_않고_검증된_LLM_aspect를_유지한다() {
        provider.result = List.of(new RestaurantFeedbackAnalysis.Item(
                FeedbackCategory.SERVICE, FeedbackAspectType.SERVICE, "주차 공간 문의 대응", FeedbackOpinionType.ETC, FeedbackSentiment.POSITIVE));
        service.analyze(fixture("주차 관련 문의에 친절하게 답해주셨어요"));

        assertThat(items.count()).isEqualTo(1);
        assertThat(items.findAll().get(0).getNormalizedAspect()).isEqualTo("주차 공간 문의 대응");
    }

    // 재리뷰 지적(MAJOR): aspectType==ETC도 MENU와 동일하게 자유-target으로 취급해야 한다.
    // 그렇지 않으면 opinionType만으로 canonicalize할 때 서로 다른 대상("국물"/"반찬"/"소스")이
    // 같은 opinionType 하나로 잘못 병합되는 false aggregation이 생긴다.
    @Test
    void aspectType이_ETC이면_같은_opinionType이어도_서로_다른_대상으로_유지된다() {
        provider.result = List.of(
                new RestaurantFeedbackAnalysis.Item(FeedbackCategory.FOOD, FeedbackAspectType.ETC, "국물", FeedbackOpinionType.TASTE, FeedbackSentiment.POSITIVE),
                new RestaurantFeedbackAnalysis.Item(FeedbackCategory.FOOD, FeedbackAspectType.ETC, "반찬", FeedbackOpinionType.TASTE, FeedbackSentiment.POSITIVE),
                new RestaurantFeedbackAnalysis.Item(FeedbackCategory.FOOD, FeedbackAspectType.ETC, "소스", FeedbackOpinionType.TASTE, FeedbackSentiment.POSITIVE)
        );
        service.analyze(fixture("국물도 반찬도 소스도 다 맛있었어요"));

        assertThat(items.count()).isEqualTo(3);
        assertThat(items.findAll())
                .extracting(com.bobfull.restaurantinsight.entity.RestaurantFeedbackItem::getNormalizedAspect)
                .containsExactlyInAnyOrder("국물", "반찬", "소스");
    }

    @Test
    void 유효하지_않은_Item만_제외하고_나머지를_저장한다() {
        provider.result = List.of(
                new RestaurantFeedbackAnalysis.Item(FeedbackCategory.FOOD, FeedbackAspectType.MENU, "탕수육", FeedbackOpinionType.TEXTURE, FeedbackSentiment.POSITIVE),
                new RestaurantFeedbackAnalysis.Item(FeedbackCategory.FOOD, FeedbackAspectType.MENU, "010-1234-5678", FeedbackOpinionType.TEXTURE, FeedbackSentiment.POSITIVE)
        );
        service.analyze(fixture("탕수육 맛 좋아요"));
        assertThat(analyses.count()).isEqualTo(1);
        assertThat(items.count()).isEqualTo(1);
        assertThat(analyses.findAll().get(0).getStatus()).isEqualTo(RestaurantFeedbackAnalysisStatus.COMPLETED);
    }

    // A3: 모든 Item이 invalid -> EXCLUDED_OUTPUT_VALIDATION, Item 0, 재전달 Provider 추가 호출 0
    @Test
    void 모든_Item이_무효하면_EXCLUDED_OUTPUT_VALIDATION으로_종료하고_재전달時_Provider를_재호출하지_않는다() {
        provider.result = List.of(
                new RestaurantFeedbackAnalysis.Item(FeedbackCategory.FOOD, FeedbackAspectType.MENU, "010-1234-5678", FeedbackOpinionType.TEXTURE, FeedbackSentiment.POSITIVE),
                new RestaurantFeedbackAnalysis.Item(FeedbackCategory.SERVICE, FeedbackAspectType.SERVICE, "김철수님", FeedbackOpinionType.FRIENDLINESS, FeedbackSentiment.POSITIVE)
        );
        Long id = fixture("탕수육 맛 좋아요");
        service.analyze(id);
        assertThat(analyses.count()).isEqualTo(1);
        assertThat(items.count()).isZero();
        assertThat(analyses.findByMessageIdAndPromptVersion(id, "v1").orElseThrow().getStatus())
                .isEqualTo(RestaurantFeedbackAnalysisStatus.EXCLUDED_OUTPUT_VALIDATION);

        service.analyze(id); // redelivery
        assertThat(analyses.count()).isEqualTo(1);
        assertThat(items.count()).isZero();
        assertThat(provider.calls.get()).isEqualTo(1);
    }

    // A4: 입력 자체에 PII가 있으면 Provider를 호출하지 않고 EXCLUDED_INPUT_PII로 terminal 처리한다.
    @Test
    void 입력에_PII가_있으면_Provider_호출_없이_EXCLUDED_INPUT_PII로_제외한다() {
        Long id = fixture("맛있었어요 010-1234-5678로 연락주세요");
        service.analyze(id);
        assertThat(provider.calls.get()).isZero();
        assertThat(items.count()).isZero();
        assertThat(analyses.findByMessageIdAndPromptVersion(id, "v1").orElseThrow().getStatus())
                .isEqualTo(RestaurantFeedbackAnalysisStatus.EXCLUDED_INPUT_PII);
    }

    // A5: 식당/음식/서비스 관련 키워드가 전혀 없으면 Candidate Gate에서 Provider를 호출하지 않는다.
    @Test
    void 식당_관련_키워드가_없으면_Candidate_Gate에서_Provider_호출_없이_EXCLUDED_CANDIDATE로_제외한다() {
        Long id = fixture("내일 몇 시에 만날까요");
        service.analyze(id);
        assertThat(provider.calls.get()).isZero();
        assertThat(items.count()).isZero();
        assertThat(analyses.findByMessageIdAndPromptVersion(id, "v1").orElseThrow().getStatus())
                .isEqualTo(RestaurantFeedbackAnalysisStatus.EXCLUDED_CANDIDATE);
    }

    // A6: relevant=false면 items가 있어도 무시하고 terminal exclude, 재전달 Provider 추가 호출 0
    @Test
    void relevant가_false면_items를_무시하고_제외하며_재전달시_Provider를_재호출하지_않는다() {
        provider.relevant = false;
        provider.result = List.of(new RestaurantFeedbackAnalysis.Item(FeedbackCategory.FOOD, FeedbackAspectType.MENU, "탕수육", FeedbackOpinionType.TEXTURE, FeedbackSentiment.POSITIVE));
        Long id = fixture("탕수육 맛 좋아요");
        service.analyze(id);
        assertThat(provider.calls.get()).isEqualTo(1);
        assertThat(items.count()).isZero();
        assertThat(analyses.findByMessageIdAndPromptVersion(id, "v1").orElseThrow().getStatus())
                .isEqualTo(RestaurantFeedbackAnalysisStatus.EXCLUDED_OUTPUT_VALIDATION);

        service.analyze(id);
        assertThat(provider.calls.get()).isEqualTo(1);
        assertThat(analyses.count()).isEqualTo(1);
        assertThat(items.count()).isZero();
    }

    // A7: relevant=true지만 items=[]인 경우도 terminal exclude, 재전달 시 동일하게 멱등
    @Test
    void items가_비어있으면_제외하고_재전달시_Provider를_재호출하지_않는다() {
        provider.result = List.of();
        Long id = fixture("탕수육 맛 좋아요");
        service.analyze(id);
        assertThat(provider.calls.get()).isEqualTo(1);
        assertThat(items.count()).isZero();
        assertThat(analyses.findByMessageIdAndPromptVersion(id, "v1").orElseThrow().getStatus())
                .isEqualTo(RestaurantFeedbackAnalysisStatus.EXCLUDED_OUTPUT_VALIDATION);

        service.analyze(id);
        assertThat(provider.calls.get()).isEqualTo(1);
        assertThat(analyses.count()).isEqualTo(1);
    }

    // A8: 순차 재전달(sequential redelivery) - Analysis/Item/Provider delta 0
    @Test
    void 순차_재전달은_Analysis_Item_Provider_증가를_유발하지_않는다() {
        Long id = fixture("탕수육 맛 좋아요");
        service.analyze(id);
        long analysesAfterFirst = analyses.count();
        long itemsAfterFirst = items.count();
        int callsAfterFirst = provider.calls.get();

        service.analyze(id);
        service.analyze(id);
        service.analyze(id);

        assertThat(analyses.count()).isEqualTo(analysesAfterFirst);
        assertThat(items.count()).isEqualTo(itemsAfterFirst);
        assertThat(provider.calls.get()).isEqualTo(callsAfterFirst);
    }

    // A9: 완전히 동시에 시작한 duplicate processing도 DB UNIQUE로 최종 결과가 정상 수렴한다.
    // (Concurrent Provider exactly-once는 보장 대상이 아니며, DB에 남는 최종 row 수렴만 검증한다.)
    // 리뷰 지적(MAJOR) 재검증: 저장을 REQUIRES_NEW로 분리했으므로, 경쟁에서 진 호출도 예외 없이
    // 정상 종료해야 한다(UnexpectedRollbackException이 호출자까지 전파되면 안 됨).
    @Test
    void 동시_중복_처리도_최종적으로_Analysis와_Item이_중복없이_수렴하고_호출자에게_예외가_전파되지_않는다() throws InterruptedException {
        Long id = fixture("탕수육 맛 좋아요");
        int threadCount = 8;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        java.util.List<Throwable> unexpectedFailures = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try { start.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    try { service.analyze(id); } catch (Throwable failure) { unexpectedFailures.add(failure); }
                });
            }
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
        } finally {
            pool.shutdown();
            pool.awaitTermination(15, TimeUnit.SECONDS);
        }

        assertThat(unexpectedFailures).isEmpty();
        assertThat(analyses.findAll()).hasSize(1);
        assertThat(analyses.count()).isEqualTo(1);
        // 저장된 단일 Analysis의 Item만 존재하고 중복 Item이 없다.
        assertThat(items.count()).isEqualTo(1);
    }

    Long fixture(String content) {
        Restaurant r = restaurants.save(Restaurant.create(1L, "r", "a", "c", "d", "k", 1));
        SharedTable t = tables.save(SharedTable.create(r.getId(), 2));
        TimeSlot s = slots.save(TimeSlot.create(t.getId(), Instant.now(), Instant.now().plusSeconds(3600)));
        Reservation v = reservations.save(Reservation.create(s.getId(), 1L));
        ChatRoom room = rooms.save(ChatRoom.create(v.getId()));
        return messages.save(ChatMessage.create(room.getId(), 1L, 1L, content)).getId();
    }

    @TestConfiguration
    static class Config {
        @Bean @Primary FakeProvider fakeProvider() { return new FakeProvider(); }
    }

    static class FakeProvider implements RestaurantFeedbackInsightPort {
        AtomicInteger calls = new AtomicInteger();
        volatile boolean relevant = true;
        volatile List<RestaurantFeedbackAnalysis.Item> result =
                List.of(new RestaurantFeedbackAnalysis.Item(FeedbackCategory.FOOD, FeedbackAspectType.MENU, "탕수육", FeedbackOpinionType.TEXTURE, FeedbackSentiment.POSITIVE));

        @Override
        public Result analyze(String content) {
            calls.incrementAndGet();
            return new Result(new RestaurantFeedbackAnalysis(relevant, result), "fake", "fake");
        }

        void reset() {
            calls.set(0);
            relevant = true;
            result = List.of(new RestaurantFeedbackAnalysis.Item(FeedbackCategory.FOOD, FeedbackAspectType.MENU, "탕수육", FeedbackOpinionType.TEXTURE, FeedbackSentiment.POSITIVE));
        }
    }
}
