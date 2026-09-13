package com.bobfull.restaurant.image.infrastructure.storage;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 인증된 OWNER ID를 포함한 임시·최종 S3 Object Key를 생성한다.
 */
@Component
public class RestaurantImageKeyGenerator {

    public RestaurantImageKeys generate(Long ownerMemberId, String extension) {
        String uuid = UUID.randomUUID().toString();
        String fileName = uuid + "." + extension;
        return new RestaurantImageKeys(
                "temp/restaurants/" + ownerMemberId + "/" + fileName,
                "restaurants/" + ownerMemberId + "/" + fileName
        );
    }

    public record RestaurantImageKeys(String tempImageKey, String finalImageKey) {
    }
}
