package com.bobfull.restaurant.image.infrastructure.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bobfull.common.exception.CustomException;
import com.bobfull.common.exception.ImageErrorCode;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.restaurant.image.infrastructure.config.RestaurantImageS3Properties;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class S3RestaurantImageStorageAdapterTest {

    private final BusinessMetricRecorder businessMetricRecorder = mock(BusinessMetricRecorder.class);

    @Test
    void 업로드_url은_content_type만_서명_헤더에_포함한다() {
        // given
        RestaurantImageS3Properties properties = new RestaurantImageS3Properties(
                "bobfull-test-image-bucket",
                Duration.ofMinutes(5),
                Duration.ofMinutes(5)
        );
        try (S3Presigner s3Presigner = S3Presigner.builder()
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-access-key", "test-secret-key")))
                .build()) {
            S3RestaurantImageStorageAdapter adapter = new S3RestaurantImageStorageAdapter(
                    mock(S3Client.class),
                    s3Presigner,
                    properties,
                    businessMetricRecorder
            );

            // when
            String uploadUrl = adapter.createUploadUrl(
                    "temp/restaurants/1/11111111-1111-1111-1111-111111111111.png",
                    "image/png",
                    1024L,
                    Duration.ofMinutes(5)
            );

            // then
            assertThat(queryParameter(uploadUrl, "X-Amz-SignedHeaders")).isEqualTo("content-type;host");
        }
    }

    @Test
    void s3_조회_요청이_실패하면_ERROR_구조화_로그를_남긴다() {
        // given
        String imageKey = "restaurants/1/11111111-1111-1111-1111-111111111111.png";
        RestaurantImageS3Properties properties = new RestaurantImageS3Properties(
                "bobfull-test-image-bucket",
                Duration.ofMinutes(5),
                Duration.ofMinutes(5)
        );
        S3Client s3Client = mock(S3Client.class);
        given(s3Client.headObject(any(HeadObjectRequest.class)))
                .willThrow(S3Exception.builder().statusCode(500).message("server error").build());
        S3RestaurantImageStorageAdapter adapter = new S3RestaurantImageStorageAdapter(
                s3Client,
                mock(S3Presigner.class),
                properties,
                businessMetricRecorder
        );
        Logger logger = (Logger) LoggerFactory.getLogger(S3RestaurantImageStorageAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        // when
        Throwable result;
        try {
            result = catchThrowable(() -> adapter.exists(imageKey));
        } finally {
            logger.detachAppender(appender);
        }

        // then
        assertThat(result).isInstanceOf(CustomException.class);
        assertThat(((CustomException) result).getErrorCode())
                .isEqualTo(ImageErrorCode.IMAGE_STORAGE_REQUEST_FAILED);
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("event=IMAGE_STORAGE_REQUEST_FAILED");
            assertThat(event.getFormattedMessage()).contains("operation=EXISTS");
            assertThat(event.getFormattedMessage()).contains("imageKey=" + imageKey);
            assertThat(event.getFormattedMessage()).contains("reason=S3_EXCEPTION");
            assertThat(event.getFormattedMessage()).contains("statusCode=500");
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(S3Exception.class.getName());
        });
    }

    private String queryParameter(String url, String name) {
        return Arrays.stream(URI.create(url).getRawQuery().split("&"))
                .map(parameter -> parameter.split("=", 2))
                .filter(parameter -> parameter.length == 2)
                .filter(parameter -> parameter[0].equals(name))
                .map(parameter -> URLDecoder.decode(parameter[1], StandardCharsets.UTF_8))
                .findFirst()
                .orElse(null);
    }
}
