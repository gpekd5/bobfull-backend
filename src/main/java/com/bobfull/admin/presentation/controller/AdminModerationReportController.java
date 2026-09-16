package com.bobfull.admin.presentation.controller;

import com.bobfull.admin.application.service.AdminModerationReportService;
import com.bobfull.admin.presentation.request.AdminReportReviewRequest;
import com.bobfull.admin.presentation.response.AdminModerationReportDetailResponse;
import com.bobfull.admin.presentation.response.AdminModerationReportResponse;
import com.bobfull.auth.application.model.AuthMember;
import com.bobfull.chat.domain.entity.ReportStatus;
import com.bobfull.common.response.ApiResponse;
import com.bobfull.common.response.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/moderation/reports")
@RequiredArgsConstructor
public class AdminModerationReportController {

    private final AdminModerationReportService service;

    @GetMapping
    public ApiResponse<PageResponse<AdminModerationReportResponse>> list(
            @RequestParam(required = false) ReportStatus status,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.success(service.list(status, pageable));
    }

    @GetMapping("/{reportId}")
    public ApiResponse<AdminModerationReportDetailResponse> get(@PathVariable Long reportId) {
        return ApiResponse.success(service.get(reportId));
    }

    @PatchMapping("/{reportId}/review")
    public ApiResponse<AdminModerationReportResponse> review(
            @AuthenticationPrincipal AuthMember admin,
            @PathVariable Long reportId,
            @Valid @RequestBody AdminReportReviewRequest request
    ) {
        return ApiResponse.success(service.review(reportId, admin.id(), request));
    }
}
