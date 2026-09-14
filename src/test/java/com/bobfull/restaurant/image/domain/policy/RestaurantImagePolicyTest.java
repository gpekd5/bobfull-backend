package com.bobfull.restaurant.image.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.bobfull.common.exception.CustomException;
import com.bobfull.restaurant.image.domain.exception.ImageErrorCode;
import com.bobfull.restaurant.image.domain.policy.RestaurantImagePolicy.ImageUploadSpec;
import org.junit.jupiter.api.Test;

class RestaurantImagePolicyTest {

    private final RestaurantImagePolicy restaurantImagePolicy = new RestaurantImagePolicy();

    @Test
    void jpg와_png만_업로드_요청으로_허용한다() {
        // when
        ImageUploadSpec jpgSpec = restaurantImagePolicy.validateUploadRequest(".JPG", "image/jpeg", 1024);
        ImageUploadSpec pngSpec = restaurantImagePolicy.validateUploadRequest("png", "image/png", 1024);

        // then
        assertThat(jpgSpec.extension()).isEqualTo("jpg");
        assertThat(jpgSpec.contentType()).isEqualTo("image/jpeg");
        assertThat(pngSpec.extension()).isEqualTo("png");
        assertThat(pngSpec.contentType()).isEqualTo("image/png");
    }

    @Test
    void webp_확장자는_허용하지_않는다() {
        // when
        Throwable result = catchThrowable(() ->
                restaurantImagePolicy.validateUploadRequest("webp", "image/webp", 1024));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(ImageErrorCode.INVALID_IMAGE_EXTENSION);
    }

    @Test
    void 확장자와_content_type이_맞지_않으면_예외가_발생한다() {
        // when
        Throwable result = catchThrowable(() ->
                restaurantImagePolicy.validateUploadRequest("png", "image/jpeg", 1024));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode())
                .isEqualTo(ImageErrorCode.IMAGE_EXTENSION_CONTENT_TYPE_MISMATCH);
    }

    @Test
    void 파일_크기가_5MB를_초과하면_예외가_발생한다() {
        // when
        Throwable result = catchThrowable(() ->
                restaurantImagePolicy.validateUploadRequest("png", "image/png", RestaurantImagePolicy.MAX_FILE_SIZE + 1));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(ImageErrorCode.IMAGE_FILE_SIZE_EXCEEDED);
    }

    @Test
    void 최종_이미지_key는_인증_owner의_restaurants_경로만_허용한다() {
        // when
        Throwable result = catchThrowable(() ->
                restaurantImagePolicy.validateFinalImageKey(
                        1L, "restaurants/2/11111111-1111-1111-1111-111111111111.png"));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(ImageErrorCode.INVALID_RESTAURANT_IMAGE_KEY);
    }

    @Test
    void 최종_이미지_key의_확장자가_허용되지_않으면_key_예외가_발생한다() {
        // when
        Throwable result = catchThrowable(() ->
                restaurantImagePolicy.validateFinalImageKey(
                        1L, "restaurants/1/11111111-1111-1111-1111-111111111111.webp"));

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode()).isEqualTo(ImageErrorCode.INVALID_RESTAURANT_IMAGE_KEY);
    }
}
