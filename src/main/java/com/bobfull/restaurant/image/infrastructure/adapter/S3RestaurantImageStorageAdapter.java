package com.bobfull.restaurant.image.infrastructure.adapter;

import com.bobfull.common.exception.CustomException;
import com.bobfull.restaurant.image.domain.exception.ImageErrorCode;
import com.bobfull.common.monitoring.BusinessMetricEvent;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.restaurant.image.infrastructure.config.RestaurantImageS3Properties;
import com.bobfull.restaurant.image.application.port.RestaurantImageStoragePort;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Component
public class S3RestaurantImageStorageAdapter implements RestaurantImageStoragePort {

    private static final Logger log = LoggerFactory.getLogger(S3RestaurantImageStorageAdapter.class);

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final RestaurantImageS3Properties properties;
    private final BusinessMetricRecorder businessMetricRecorder;

    public S3RestaurantImageStorageAdapter(
            S3Client s3Client,
            S3Presigner s3Presigner,
            RestaurantImageS3Properties properties,
            BusinessMetricRecorder businessMetricRecorder
    ) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.properties = properties;
        this.businessMetricRecorder = businessMetricRecorder;
    }

    @Override
    public String createUploadUrl(String imageKey, String contentType, long contentLength, Duration expiration) {
        validateBucket();
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(properties.imageBucket())
                .key(imageKey)
                .contentType(contentType)
                .build();
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(expiration)
                .putObjectRequest(putObjectRequest)
                .build();
        try {
            return s3Presigner.presignPutObject(presignRequest).url().toString();
        } catch (RuntimeException exception) {
            throw imageStorageRequestFailed("CREATE_UPLOAD_URL", imageKey, exception);
        }
    }

    @Override
    public String createGetUrl(String imageKey, Duration expiration) {
        if (!StringUtils.hasText(imageKey)) {
            return null;
        }
        validateBucket();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(expiration)
                .getObjectRequest(request -> request.bucket(properties.imageBucket()).key(imageKey))
                .build();
        try {
            return s3Presigner.presignGetObject(presignRequest).url().toString();
        } catch (RuntimeException exception) {
            throw imageStorageRequestFailed("CREATE_GET_URL", imageKey, exception);
        }
    }

    @Override
    public boolean exists(String imageKey) {
        validateBucket();
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.imageBucket())
                    .key(imageKey)
                    .build());
            return true;
        } catch (NoSuchKeyException exception) {
            return false;
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                return false;
            }
            throw imageStorageRequestFailed("EXISTS", imageKey, exception);
        } catch (RuntimeException exception) {
            throw imageStorageRequestFailed("EXISTS", imageKey, exception);
        }
    }

    @Override
    public void delete(String imageKey) {
        if (!StringUtils.hasText(imageKey)) {
            return;
        }
        validateBucket();
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.imageBucket())
                    .key(imageKey)
                    .build());
        } catch (RuntimeException exception) {
            throw new CustomException(ImageErrorCode.IMAGE_STORAGE_REQUEST_FAILED);
        }
    }

    private CustomException imageStorageRequestFailed(String operation, String imageKey, S3Exception exception) {
        log.error(
                "event=IMAGE_STORAGE_REQUEST_FAILED operation={} imageKey={} reason=S3_EXCEPTION statusCode={}",
                operation,
                imageKey,
                exception.statusCode(),
                exception
        );
        businessMetricRecorder.increment(BusinessMetricEvent.IMAGE_STORAGE_REQUEST_FAILED);
        return new CustomException(ImageErrorCode.IMAGE_STORAGE_REQUEST_FAILED);
    }

    private CustomException imageStorageRequestFailed(String operation, String imageKey, RuntimeException exception) {
        log.error(
                "event=IMAGE_STORAGE_REQUEST_FAILED operation={} imageKey={} reason={}",
                operation,
                imageKey,
                exception.getClass().getSimpleName(),
                exception
        );
        businessMetricRecorder.increment(BusinessMetricEvent.IMAGE_STORAGE_REQUEST_FAILED);
        return new CustomException(ImageErrorCode.IMAGE_STORAGE_REQUEST_FAILED);
    }

    private void validateBucket() {
        if (!StringUtils.hasText(properties.imageBucket())) {
            throw new CustomException(ImageErrorCode.IMAGE_STORAGE_NOT_CONFIGURED);
        }
    }
}
