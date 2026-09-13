package com.bobfull.restaurant.image.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@EnableConfigurationProperties(RestaurantImageS3Properties.class)
public class RestaurantImageS3Config {

    @Bean
    public S3Client s3Client(@Value("${aws.region:ap-northeast-2}") String region) {
        return S3Client.builder()
                .region(Region.of(region))
                .build();
    }

    @Bean
    public S3Presigner s3Presigner(@Value("${aws.region:ap-northeast-2}") String region) {
        return S3Presigner.builder()
                .region(Region.of(region))
                .build();
    }
}
