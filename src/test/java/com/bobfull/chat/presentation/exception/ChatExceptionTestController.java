package com.bobfull.chat.presentation.exception;

import com.bobfull.chat.domain.entity.ChatRoomMemberReport;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ChatExceptionTestController {

    @PatchMapping("/api/test/chat-report-lock")
    void review() {
        throw new ObjectOptimisticLockingFailureException(ChatRoomMemberReport.class, 1L);
    }
}
