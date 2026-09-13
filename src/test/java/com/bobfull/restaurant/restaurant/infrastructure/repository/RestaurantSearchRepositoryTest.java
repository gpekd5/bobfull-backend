package com.bobfull.restaurant.restaurant.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.restaurant.restaurant.presentation.dto.RestaurantSearchRequest;
import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;
import com.bobfull.restaurant.sharedtable.domain.entity.SharedTable;
import com.bobfull.restaurant.sharedtable.infrastructure.repository.SharedTableRepository;
import com.bobfull.restaurant.timeslot.domain.entity.TimeSlot;
import com.bobfull.restaurant.timeslot.infrastructure.repository.TimeSlotRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:restaurant-search-repository-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RestaurantSearchRepositoryTest {

    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

    @Autowired
    private RestaurantRepository restaurantRepository;

    @Autowired
    private SharedTableRepository sharedTableRepository;

    @Autowired
    private TimeSlotRepository timeSlotRepository;

    @Test
    void 조건이_없으면_삭제되지_않은_ACTIVE_식당을_기본_목록으로_반환한다() {
        // given
        Restaurant activeRestaurant = restaurantRepository.save(
                restaurant("밥풀식당", "한식", "흑돼지,혼밥"));
        Restaurant deletedRestaurant = restaurantRepository.save(
                restaurant("삭제식당", "양식", "파스타"));
        deletedRestaurant.softDelete(Instant.parse("2026-07-30T00:00:00Z"));
        restaurantRepository.flush();

        // when
        Page<Restaurant> result = restaurantRepository.search(
                new RestaurantSearchRequest(null, null, null, null),
                PageRequest.of(0, 20));

        // then
        assertThat(result.getContent()).containsExactly(activeRestaurant);
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void 키워드_카테고리_날짜_시간이_모두_맞는_식당만_검색한다() {
        // given
        Restaurant matched = restaurantRepository.save(restaurant("밥풀식당", "한식", "흑돼지,혼밥"));
        Restaurant other = restaurantRepository.save(restaurant("초밥집", "일식", "스시"));
        createSession(matched, "2026-08-01T18:00:00");
        createSession(other, "2026-08-01T19:00:00");

        // when
        Page<Restaurant> result = restaurantRepository.search(
                new RestaurantSearchRequest(
                        "흑돼지", "한식", LocalDate.of(2026, 8, 1), LocalTime.of(18, 0)),
                PageRequest.of(0, 20));

        // then
        assertThat(result.getContent()).containsExactly(matched);
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void 날짜_조건에_맞는_회차가_없으면_빈_목록을_반환한다() {
        // given
        Restaurant restaurant = restaurantRepository.save(restaurant("밥풀식당", "한식", "흑돼지,혼밥"));
        createSession(restaurant, "2026-08-01T18:00:00");

        // when
        Page<Restaurant> result = restaurantRepository.search(
                new RestaurantSearchRequest(null, null, LocalDate.of(2026, 8, 2), null),
                PageRequest.of(0, 20));

        // then
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    void 시간만_전달하면_서울_기준_회차_시작_시간으로_검색한다() {
        // given
        Restaurant matched = restaurantRepository.save(restaurant("저녁식당", "한식", "흑돼지"));
        Restaurant other = restaurantRepository.save(restaurant("점심식당", "한식", "갈치조림"));
        createSession(matched, "2026-08-01T18:00:00");
        createSession(other, "2026-08-01T12:00:00");

        // when
        Page<Restaurant> result = restaurantRepository.search(
                new RestaurantSearchRequest(null, null, null, LocalTime.of(18, 0)),
                PageRequest.of(0, 20));

        // then
        assertThat(result.getContent()).containsExactly(matched);
    }

    @Test
    void recent_정렬은_식당_최신_등록순으로_조회한다() {
        // given
        Restaurant oldRestaurant = restaurantRepository.save(restaurant("먼저등록식당", "한식", "흑돼지"));
        Restaurant recentRestaurant = restaurantRepository.save(restaurant("나중등록식당", "한식", "갈치조림"));
        restaurantRepository.flush();

        // when
        Page<Restaurant> result = restaurantRepository.search(
                new RestaurantSearchRequest(null, null, null, null),
                PageRequest.of(0, 20, Sort.by(Sort.Order.desc("recent"))));

        // then
        assertThat(result.getContent()).containsExactly(recentRestaurant, oldRestaurant);
    }

    private Restaurant restaurant(String name, String category, String keyword) {
        return Restaurant.create(1L, name, "제주시 애월읍 1", category, "설명", keyword, 10000);
    }

    private void createSession(Restaurant restaurant, String startAt) {
        SharedTable table = sharedTableRepository.save(SharedTable.create(restaurant.getId(), 4));
        timeSlotRepository.save(TimeSlot.create(
                table.getId(),
                toInstant(startAt),
                toInstant(startAt).plusSeconds(7_200)
        ));
    }

    private Instant toInstant(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(SEOUL_ZONE).toInstant();
    }
}
