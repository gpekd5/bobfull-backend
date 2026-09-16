package com.bobfull.admin.presentation.request;

import com.bobfull.chat.domain.entity.ReviewDecision;
import jakarta.validation.constraints.NotNull;

public record AdminReportReviewRequest(
        @NotNull ReviewDecision decision
) {
}
