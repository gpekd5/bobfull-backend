package com.bobfull.restaurant.image.application.port;

import java.time.Duration;

// Application이 이미지 저장소에 요구하는 URL 발급·객체 확인·삭제 경계다.
public interface RestaurantImageStoragePort {

    String createUploadUrl(String imageKey, String contentType, long contentLength, Duration expiration);

    String createGetUrl(String imageKey, Duration expiration);

    boolean exists(String imageKey);

    void delete(String imageKey);
}
