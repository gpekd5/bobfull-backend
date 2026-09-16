package com.bobfull.restaurant.image.domain.exception;

import com.bobfull.common.exception.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 식당 이미지 업로드와 S3 객체 검증 전용 에러 코드다.
 */
@Getter
@RequiredArgsConstructor
public enum ImageErrorCode implements BaseErrorCode {

    INVALID_IMAGE_EXTENSION(HttpStatus.BAD_REQUEST, "허용하지 않는 이미지 확장자입니다."),
    UNSUPPORTED_IMAGE_CONTENT_TYPE(HttpStatus.BAD_REQUEST, "허용하지 않는 이미지 Content-Type입니다."),
    IMAGE_EXTENSION_CONTENT_TYPE_MISMATCH(HttpStatus.BAD_REQUEST, "이미지 확장자와 Content-Type이 일치하지 않습니다."),
    IMAGE_FILE_SIZE_EXCEEDED(HttpStatus.BAD_REQUEST, "이미지 파일 크기가 허용 범위를 초과했습니다."),
    INVALID_RESTAURANT_IMAGE_KEY(HttpStatus.BAD_REQUEST, "식당 이미지 Key가 올바르지 않습니다."),
    RESTAURANT_IMAGE_NOT_FOUND(HttpStatus.BAD_REQUEST, "검증 완료된 식당 이미지를 찾을 수 없습니다."),
    RESTAURANT_IMAGE_ALREADY_USED(HttpStatus.BAD_REQUEST, "이미 다른 식당에 연결된 이미지입니다."),
    IMAGE_STORAGE_NOT_CONFIGURED(HttpStatus.INTERNAL_SERVER_ERROR, "이미지 저장소 설정이 올바르지 않습니다."),
    IMAGE_STORAGE_REQUEST_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "이미지 저장소 요청에 실패했습니다.");

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
