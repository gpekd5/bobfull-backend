package com.bobfull.restaurant.image.application.service;

import com.bobfull.common.exception.CustomException;
import com.bobfull.restaurant.image.domain.exception.ImageErrorCode;
import com.bobfull.restaurant.image.domain.policy.RestaurantImagePolicy;
import com.bobfull.restaurant.image.infrastructure.config.RestaurantImageS3Properties;
import com.bobfull.restaurant.image.infrastructure.storage.RestaurantImageKeyGenerator;
import com.bobfull.restaurant.image.presentation.request.RestaurantImageUploadUrlRequest;
import com.bobfull.restaurant.image.presentation.response.RestaurantImageUploadUrlResponse;
import com.bobfull.restaurant.image.application.port.RestaurantImageStoragePort;
import com.bobfull.restaurant.image.infrastructure.storage.RestaurantImageKeyGenerator.RestaurantImageKeys;
import com.bobfull.restaurant.image.domain.policy.RestaurantImagePolicy.ImageUploadSpec;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

// 식당 이미지 업로드 조건·key·S3 객체를 검증하고 presigned URL을 발급한다.
@Service
public class RestaurantImageService {

    private final RestaurantImagePolicy restaurantImagePolicy;
    private final RestaurantImageKeyGenerator restaurantImageKeyGenerator;
    private final RestaurantImageStoragePort restaurantImageStoragePort;
    private final RestaurantImageS3Properties restaurantImageS3Properties;

    public RestaurantImageService(
            RestaurantImagePolicy restaurantImagePolicy,
            RestaurantImageKeyGenerator restaurantImageKeyGenerator,
            RestaurantImageStoragePort restaurantImageStoragePort,
            RestaurantImageS3Properties restaurantImageS3Properties
    ) {
        this.restaurantImagePolicy = restaurantImagePolicy;
        this.restaurantImageKeyGenerator = restaurantImageKeyGenerator;
        this.restaurantImageStoragePort = restaurantImageStoragePort;
        this.restaurantImageS3Properties = restaurantImageS3Properties;
    }

    // 업로드 조건을 검증하고 임시·최종 key와 presigned 업로드 URL을 생성한다.
    public RestaurantImageUploadUrlResponse createUploadUrl(
            Long ownerMemberId,
            RestaurantImageUploadUrlRequest request
    ) {
        ImageUploadSpec spec = restaurantImagePolicy.validateUploadRequest(
                request.extension(),
                request.contentType(),
                request.fileSize()
        );
        RestaurantImageKeys imageKeys = restaurantImageKeyGenerator.generate(ownerMemberId, spec.extension());
        String uploadUrl = restaurantImageStoragePort.createUploadUrl(
                imageKeys.tempImageKey(),
                spec.contentType(),
                spec.fileSize(),
                restaurantImageS3Properties.uploadUrlExpiration()
        );
        return new RestaurantImageUploadUrlResponse(
                uploadUrl,
                imageKeys.tempImageKey(),
                imageKeys.finalImageKey()
        );
    }

    // 최종 key가 요청 소유자 경로에 속하고 실제 저장소에 존재하는지 확인한다.
    public void validateFinalImage(Long ownerMemberId, String imageKey) {
        restaurantImagePolicy.validateFinalImageKey(ownerMemberId, imageKey);
        if (!restaurantImageStoragePort.exists(imageKey)) {
            throw new CustomException(ImageErrorCode.RESTAURANT_IMAGE_NOT_FOUND);
        }
    }

    public String createGetUrl(String imageKey) {
        if (!StringUtils.hasText(imageKey)) {
            return null;
        }
        return restaurantImageStoragePort.createGetUrl(imageKey, restaurantImageS3Properties.getUrlExpiration());
    }

    public void delete(String imageKey) {
        if (StringUtils.hasText(imageKey)) {
            restaurantImageStoragePort.delete(imageKey);
        }
    }
}
