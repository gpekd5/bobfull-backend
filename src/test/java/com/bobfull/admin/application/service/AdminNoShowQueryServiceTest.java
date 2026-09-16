package com.bobfull.admin.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;

import com.bobfull.admin.application.result.AdminNoShowResult;
import com.bobfull.admin.infrastructure.repository.query.AdminNoShowRepository;
import com.bobfull.common.response.PageResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class AdminNoShowQueryServiceTest {

    @Mock private AdminNoShowRepository adminNoShowRepository;

    @InjectMocks private AdminNoShowQueryService service;

    @Test
    void 필터_없이_조회하면_레포지토리_결과를_그대로_응답으로_변환한다() {
        Pageable pageable = PageRequest.of(0, 20);
        AdminNoShowResult result = new AdminNoShowResult(
                1L, 15L, "홍길동", 2L, "밥풀식당", 101L, 501L, 2, Instant.parse("2026-07-25T12:00:00Z"));
        Page<AdminNoShowResult> page = new PageImpl<>(List.of(result), pageable, 1);
        given(adminNoShowRepository.searchNoShows(isNull(), isNull(), eq(pageable))).willReturn(page);

        PageResponse<?> response = service.getNoShows(null, null, pageable);

        assertThat(response.content()).hasSize(1);
        assertThat(response.totalElements()).isEqualTo(1);
    }

    @Test
    void memberId_restaurantId_필터를_레포지토리에_그대로_전달한다() {
        Pageable pageable = PageRequest.of(0, 20);
        given(adminNoShowRepository.searchNoShows(eq(15L), eq(2L), eq(pageable)))
                .willReturn(new PageImpl<>(List.of(), pageable, 0));

        service.getNoShows(15L, 2L, pageable);

        org.mockito.Mockito.verify(adminNoShowRepository).searchNoShows(15L, 2L, pageable);
    }
}
