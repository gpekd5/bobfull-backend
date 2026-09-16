package com.bobfull.admin.infrastructure.repository.query;

import com.bobfull.admin.application.result.AdminNoShowResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AdminNoShowRepository {

    Page<AdminNoShowResult> searchNoShows(Long memberId, Long restaurantId, Pageable pageable);
}
