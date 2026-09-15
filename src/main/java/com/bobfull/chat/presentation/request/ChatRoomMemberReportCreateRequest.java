package com.bobfull.chat.presentation.request;

import com.bobfull.chat.domain.entity.ReportReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChatRoomMemberReportCreateRequest(
        @NotNull ReportReason reason,
        Long anchorMessageId,
        @Size(max = 500) String detail
) {
}
