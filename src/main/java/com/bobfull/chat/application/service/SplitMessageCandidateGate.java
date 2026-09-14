package com.bobfull.chat.application.service;

import com.bobfull.chat.domain.entity.ChatMessage;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/** #251의 넓은 Context 재적용을 막기 위한 #266 Split 후보 제한이다. */
@Component
public class SplitMessageCandidateGate {
    static final int MAX_FRAGMENT_LENGTH = 8;
    static final int RECENT_MESSAGE_LIMIT = 5;
    static final Duration CONTEXT_WINDOW = Duration.ofSeconds(30);

    boolean mayNeedContext(ChatMessage current) {
        return current.getCreatedAt() != null && current.getContent().codePointCount(0, current.getContent().length()) <= MAX_FRAGMENT_LENGTH;
    }

    boolean isSplitCandidate(List<ChatMessage> messages, SplitMessageContext context) {
        return context.containsMultipleMessages()
                && context.recentCanonicalCandidates().stream().anyMatch(SplitMessageCandidateGate::containsSuspiciousFragment);
    }

    private static boolean containsSuspiciousFragment(String joined) {
        return joined.contains("시") || joined.contains("병") || joined.contains("개") || joined.contains("죽")
                || joined.startsWith("010");
    }
}
