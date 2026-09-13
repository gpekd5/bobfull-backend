package com.bobfull.restaurant.image.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aws.s3")
public record RestaurantImageS3Properties(
        String imageBucket,
        Duration uploadUrlExpiration,
        Duration getUrlExpiration
) {
    public RestaurantImageS3Properties {
        if (uploadUrlExpiration == null) {
            uploadUrlExpiration = Duration.ofMinutes(5);
        }
        if (getUrlExpiration == null) {
            getUrlExpiration = Duration.ofMinutes(5);
        }
    }
}
