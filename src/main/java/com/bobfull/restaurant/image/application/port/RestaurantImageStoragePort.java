package com.bobfull.restaurant.image.application.port;

import java.time.Duration;

public interface RestaurantImageStoragePort {

    String createUploadUrl(String imageKey, String contentType, long contentLength, Duration expiration);

    String createGetUrl(String imageKey, Duration expiration);

    boolean exists(String imageKey);

    void delete(String imageKey);
}
