package com.bobfull.admin.application.service;

import com.bobfull.admin.presentation.response.AdminNoShowListItemResponse;
import com.bobfull.admin.application.result.AdminNoShowResult;
import com.bobfull.admin.infrastructure.repository.query.AdminNoShowRepository;
import com.bobfull.common.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ADMIN의 전체 노쇼 현황 조회를 담당한다(Issue #134 §11-8). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminNoShowQueryService {

    private final AdminNoShowRepository adminNoShowRepository;

    public PageResponse<AdminNoShowListItemResponse> getNoShows(Long memberId, Long restaurantId, Pageable pageable) {
        Page<AdminNoShowResult> results = adminNoShowRepository.searchNoShows(memberId, restaurantId, pageable);
        return PageResponse.from(results.map(AdminNoShowListItemResponse::of));
    }
}
