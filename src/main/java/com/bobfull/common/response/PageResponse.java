package com.bobfull.common.response;

import java.util.List;
import org.springframework.data.domain.Page;

// API 명세의 공통 페이지 메타데이터와 목록을 제공한다.
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
