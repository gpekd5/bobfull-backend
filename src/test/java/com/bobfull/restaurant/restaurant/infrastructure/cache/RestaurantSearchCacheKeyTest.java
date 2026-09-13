package com.bobfull.restaurant.restaurant.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.restaurant.restaurant.presentation.dto.RestaurantSearchRequest;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * 검색 Cache Key 정규화·중복 방지 규칙을 검증한다(Issue #62 Cache Key 계약).
 */
class RestaurantSearchCacheKeyTest {

    @Test
    void keyword_대소문자와_앞뒤_공백이_달라도_같은_key로_정규화된다() {
        RestaurantSearchRequest first = new RestaurantSearchRequest(" 맛집 ", null, null, null);
        RestaurantSearchRequest second = new RestaurantSearchRequest("맛집", null, null, null);
        var pageable = PageRequest.of(0, 20);

        String firstDigest = RestaurantSearchCacheKey.of(first, pageable).digest();
        String secondDigest = RestaurantSearchCacheKey.of(second, pageable).digest();

        assertThat(firstDigest).isEqualTo(secondDigest);
    }

    @Test
    void keyword가_다르면_다른_key다() {
        var pageable = PageRequest.of(0, 20);
        String a = RestaurantSearchCacheKey.of(new RestaurantSearchRequest("한식", null, null, null), pageable).digest();
        String b = RestaurantSearchCacheKey.of(new RestaurantSearchRequest("일식", null, null, null), pageable).digest();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void page와_size가_다르면_다른_key다() {
        RestaurantSearchRequest request = new RestaurantSearchRequest(null, null, null, null);
        String page0 = RestaurantSearchCacheKey.of(request, PageRequest.of(0, 20)).digest();
        String page1 = RestaurantSearchCacheKey.of(request, PageRequest.of(1, 20)).digest();
        String size10 = RestaurantSearchCacheKey.of(request, PageRequest.of(0, 10)).digest();

        assertThat(page0).isNotEqualTo(page1);
        assertThat(page0).isNotEqualTo(size10);
    }

    @Test
    void 정렬_조건이_다르면_다른_key다() {
        RestaurantSearchRequest request = new RestaurantSearchRequest(null, null, null, null);
        String nameAsc = RestaurantSearchCacheKey.of(request, PageRequest.of(0, 20, Sort.by("name").ascending())).digest();
        String nameDesc = RestaurantSearchCacheKey.of(request, PageRequest.of(0, 20, Sort.by("name").descending())).digest();

        assertThat(nameAsc).isNotEqualTo(nameDesc);
    }

    @Test
    void date나_time이_있으면_캐시_대상에서_제외된다() {
        assertThat(RestaurantSearchCacheKey.isCacheEligible(
                new RestaurantSearchRequest(null, null, null, null))).isTrue();
        assertThat(RestaurantSearchCacheKey.isCacheEligible(
                new RestaurantSearchRequest(null, null, LocalDate.of(2026, 8, 12), null))).isFalse();
        assertThat(RestaurantSearchCacheKey.isCacheEligible(
                new RestaurantSearchRequest(null, null, null, LocalTime.of(18, 0)))).isFalse();
    }
}
