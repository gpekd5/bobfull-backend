package com.bobfull.restaurant.image.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.bobfull.common.exception.CustomException;
import com.bobfull.restaurant.image.domain.exception.ImageErrorCode;
import com.bobfull.restaurant.image.domain.policy.RestaurantImagePolicy;
import com.bobfull.restaurant.image.infrastructure.config.RestaurantImageS3Properties;
import com.bobfull.restaurant.image.infrastructure.storage.RestaurantImageKeyGenerator;
import com.bobfull.restaurant.image.presentation.dto.RestaurantImageUploadUrlRequest;
import com.bobfull.restaurant.image.presentation.dto.RestaurantImageUploadUrlResponse;
import com.bobfull.restaurant.image.application.port.RestaurantImageStoragePort;
import com.bobfull.restaurant.image.infrastructure.storage.RestaurantImageKeyGenerator.RestaurantImageKeys;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RestaurantImageServiceTest {

    @Mock
    private RestaurantImageKeyGenerator restaurantImageKeyGenerator;

    @Mock
    private RestaurantImageStoragePort restaurantImageStoragePort;

    private RestaurantImageService restaurantImageService;

    @BeforeEach
    void setUp() {
        RestaurantImageS3Properties properties = new RestaurantImageS3Properties(
                "bobfull-test-image-bucket",
                Duration.ofMinutes(5),
                Duration.ofMinutes(5)
        );
        restaurantImageService = new RestaurantImageService(
                new RestaurantImagePolicy(),
                restaurantImageKeyGenerator,
                restaurantImageStoragePort,
                properties
        );
    }

    @Test
    void 업로드_url을_발급하면_임시_key와_최종_key를_함께_반환한다() {
        // given
        RestaurantImageUploadUrlRequest request = new RestaurantImageUploadUrlRequest("png", "image/png", 1024L);
        given(restaurantImageKeyGenerator.generate(1L, "png"))
                .willReturn(new RestaurantImageKeys(
                        "temp/restaurants/1/11111111-1111-1111-1111-111111111111.png",
                        "restaurants/1/11111111-1111-1111-1111-111111111111.png"));
        given(restaurantImageStoragePort.createUploadUrl(
                eq("temp/restaurants/1/11111111-1111-1111-1111-111111111111.png"),
                eq("image/png"),
                eq(1024L),
                eq(Duration.ofMinutes(5))
        )).willReturn("https://upload.example");

        // when
        RestaurantImageUploadUrlResponse response = restaurantImageService.createUploadUrl(1L, request);

        // then
        assertThat(response.uploadUrl()).isEqualTo("https://upload.example");
        assertThat(response.tempImageKey())
                .isEqualTo("temp/restaurants/1/11111111-1111-1111-1111-111111111111.png");
        assertThat(response.finalImageKey())
                .isEqualTo("restaurants/1/11111111-1111-1111-1111-111111111111.png");
    }

    @Test
    void 최종_이미지_key가_s3에_없으면_예외가_발생한다() {
        // given
        String imageKey = "restaurants/1/11111111-1111-1111-1111-111111111111.png";
        given(restaurantImageStoragePort.exists(imageKey)).willReturn(false);

        // when
        Throwable result = catchThrowable(() -> restaurantImageService.validateFinalImage(1L, imageKey));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(ImageErrorCode.RESTAURANT_IMAGE_NOT_FOUND);
    }

    @Test
    void 이미지_key가_없으면_조회_url을_만들지_않는다() {
        // when
        String response = restaurantImageService.createGetUrl(null);

        // then
        assertThat(response).isNull();
        verify(restaurantImageStoragePort, never()).createGetUrl(any(), any());
    }
}
