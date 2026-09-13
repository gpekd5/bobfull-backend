package com.bobfull.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.BDDMockito.given;

import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.common.exception.RestaurantErrorCode;
import com.bobfull.restaurant.restaurant.infrastructure.repository.RestaurantRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class AdminRestaurantQueryServiceTest {

    @Mock private RestaurantRepository restaurantRepository;

    @InjectMocks private AdminRestaurantQueryService service;

    @Test
    void 유효하지_않은_상태_필터는_400_예외가_발생한다() {
        Pageable pageable = PageRequest.of(0, 20);

        Throwable result = catchThrowable(() -> service.getRestaurants(null, "INVALID", null, pageable));

        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    void 존재하지_않는_restaurantId_상세조회는_404_예외가_발생한다() {
        given(restaurantRepository.findRestaurantDetail(999L)).willReturn(Optional.empty());

        Throwable result = catchThrowable(() -> service.getRestaurant(999L));

        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(RestaurantErrorCode.RESTAURANT_ID_NOT_FOUND);
    }
}
