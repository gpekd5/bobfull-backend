package com.bobfull.restaurantinsight.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bobfull.chat.domain.entity.ChatMessage;
import com.bobfull.chat.domain.entity.ChatRoom;
import com.bobfull.chat.infrastructure.repository.ChatMessageRepository;
import com.bobfull.chat.infrastructure.repository.ChatRoomRepository;
import com.bobfull.reservation.domain.entity.Reservation;
import com.bobfull.reservation.infrastructure.repository.ReservationRepository;
import com.bobfull.restaurant.restaurant.infrastructure.repository.RestaurantRepository;
import com.bobfull.restaurantinsight.port.RestaurantFeedbackInsightPort;
import com.bobfull.restaurantinsight.repository.RestaurantFeedbackInsightRepository;
import com.bobfull.restaurant.sharedtable.domain.entity.SharedTable;
import com.bobfull.restaurant.sharedtable.infrastructure.repository.SharedTableRepository;
import com.bobfull.restaurant.timeslot.domain.entity.TimeSlot;
import com.bobfull.restaurant.timeslot.infrastructure.repository.TimeSlotRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 리뷰 지적(MAJOR 3) 재검증: consumer-enabled=true인데 Insight Provider Bean이 없는(=
 * ai.restaurant-insight.enabled=false) 설정 오류 상태에서 Event를 조용히 성공 처리(offset 커밋)하면
 * 안 된다. analyze()는 예외를 던져 Kafka Retry/DLT 경계로 넘어가야 한다.
 */
class RestaurantFeedbackInsightServiceProviderMissingTest {

    @Test
    @SuppressWarnings("unchecked")
    void Provider_Bean이_없으면_조용히_성공하지_않고_예외를_던진다() {
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        ChatRoomRepository rooms = mock(ChatRoomRepository.class);
        ReservationRepository reservations = mock(ReservationRepository.class);
        TimeSlotRepository timeSlots = mock(TimeSlotRepository.class);
        SharedTableRepository tables = mock(SharedTableRepository.class);
        RestaurantRepository restaurants = mock(RestaurantRepository.class);
        RestaurantFeedbackInsightRepository insights = mock(RestaurantFeedbackInsightRepository.class);
        RestaurantInsightCandidateGate candidateGate = new RestaurantInsightCandidateGate();
        RestaurantInsightPrivacyValidator privacyValidator = new RestaurantInsightPrivacyValidator();
        ObjectProvider<RestaurantFeedbackInsightPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        Clock clock = Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC);

        ChatMessage message = ChatMessage.create(1L, 1L, 1L, "탕수육 맛 좋아요");
        var room = ChatRoom.create(10L);
        var reservation = Reservation.create(20L, 1L);
        var slot = TimeSlot.create(30L, Instant.now(), Instant.now().plusSeconds(3600));
        var table = SharedTable.create(1L, 2);

        when(insights.findByMessageIdAndPromptVersion(1L, "v1")).thenReturn(Optional.empty());
        when(messages.findById(1L)).thenReturn(Optional.of(message));
        when(rooms.findById(any())).thenReturn(Optional.of(room));
        when(reservations.findById(any())).thenReturn(Optional.of(reservation));
        when(timeSlots.findById(any())).thenReturn(Optional.of(slot));
        when(tables.findById(any())).thenReturn(Optional.of(table));
        var restaurant = com.bobfull.restaurant.restaurant.domain.entity.Restaurant.create(1L, "r", "제주시 애월읍", "한식", "d", "k", 1);
        when(restaurants.findById(any())).thenReturn(Optional.of(restaurant));

        RestaurantFeedbackInsightWriter writer = mock(RestaurantFeedbackInsightWriter.class);
        RestaurantFeedbackInsightService service = new RestaurantFeedbackInsightService(
                messages, rooms, reservations, timeSlots, tables, restaurants,
                insights, candidateGate, privacyValidator, provider, clock, "v1", writer);

        assertThatThrownBy(() -> service.analyze(1L)).isInstanceOf(IllegalStateException.class);
    }
}
