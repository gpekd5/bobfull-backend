package com.bobfull.admin.infrastructure.query;

import com.bobfull.admin.application.model.AdminMemberNoShowRateResult;
import com.bobfull.admin.application.model.AdminRestaurantStatisticsResult;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AdminStatisticsRepository {

    Page<AdminRestaurantStatisticsResult> aggregateRestaurantStatistics(Instant startAt, Instant endAt, Pageable pageable);

    Page<AdminMemberNoShowRateResult> aggregateMemberNoShowRates(Pageable pageable);
}
