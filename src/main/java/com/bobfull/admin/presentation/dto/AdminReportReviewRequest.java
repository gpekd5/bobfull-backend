package com.bobfull.admin.presentation.dto;
import com.bobfull.chat.domain.entity.ReviewDecision; import jakarta.validation.constraints.NotNull;
public record AdminReportReviewRequest(@NotNull ReviewDecision decision) { }
