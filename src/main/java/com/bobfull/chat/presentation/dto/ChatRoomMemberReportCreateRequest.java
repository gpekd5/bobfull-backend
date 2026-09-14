package com.bobfull.chat.presentation.dto;
import com.bobfull.chat.domain.entity.ReportReason;
import jakarta.validation.constraints.*;
public record ChatRoomMemberReportCreateRequest(@NotNull ReportReason reason, Long anchorMessageId, @Size(max=500) String detail) { }
