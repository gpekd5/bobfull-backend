package com.bobfull.admin.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.admin.dto.AdminRestaurantResult;
import com.bobfull.member.domain.entity.Member;
import com.bobfull.member.infrastructure.repository.MemberRepository;
import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;
import com.bobfull.restaurant.restaurant.domain.entity.RestaurantStatus;
import com.bobfull.restaurant.restaurant.infrastructure.repository.RestaurantRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;

@DataJpaTest
class AdminRestaurantRepositoryImplTest {

    @Autowired private MemberRepository memberRepository;
    @Autowired private RestaurantRepository restaurantRepository;

    @Test
    void 키워드로_식당_이름_또는_키워드를_검색하고_사장님_이름을_포함한다() {
        Member owner = memberRepository.save(
                Member.createOwner("owner@example.com", "hash", "김사장", "01011112222", "1234567890"));
        restaurantRepository.save(Restaurant.create(owner.getId(), "밥풀식당", "제주시", "한식", "설명", "흑돼지", 10000));
        restaurantRepository.save(Restaurant.create(owner.getId(), "다른식당", "제주시", "일식", "설명", "초밥", 10000));

        var result = restaurantRepository.searchRestaurants("밥풀", null, null, PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(AdminRestaurantResult::name).containsExactly("밥풀식당");
        assertThat(result.getContent()).extracting(AdminRestaurantResult::ownerName).containsExactly("김사장");
    }

    @Test
    void status로_필터링한다() {
        Member owner = memberRepository.save(
                Member.createOwner("owner@example.com", "hash", "김사장", "01011112222", "1234567890"));
        restaurantRepository.save(Restaurant.create(owner.getId(), "식당", "주소", "한식", "설명", "키워드", 10000));

        var result = restaurantRepository.searchRestaurants(null, RestaurantStatus.ACTIVE, null, PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void deleted_필터가_false이면_소프트_삭제되지_않은_식당만_반환한다() {
        Member owner = memberRepository.save(
                Member.createOwner("owner@example.com", "hash", "김사장", "01011112222", "1234567890"));
        Restaurant active = restaurantRepository.save(
                Restaurant.create(owner.getId(), "활성식당", "주소", "한식", "설명", "키워드", 10000));
        Restaurant deleted = restaurantRepository.save(
                Restaurant.create(owner.getId(), "폐업식당", "주소", "한식", "설명", "키워드", 10000));
        deleted.softDelete(Instant.parse("2026-08-01T00:00:00Z"));
        restaurantRepository.saveAndFlush(deleted);

        var result = restaurantRepository.searchRestaurants(null, null, false, PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(AdminRestaurantResult::restaurantId).containsExactly(active.getId());
    }

    @Test
    void 존재하지_않는_restaurantId_상세조회는_빈_결과를_반환한다() {
        Optional<AdminRestaurantResult> result = restaurantRepository.findRestaurantDetail(999L);

        assertThat(result).isEmpty();
    }
}
