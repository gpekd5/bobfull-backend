package com.bobfull.chat.application.service;

import com.bobfull.chat.domain.entity.ChatMessage;
import java.util.List;
import java.util.stream.IntStream;

/** #266에서만 사용하는 same-room/same-sender의 짧은 메시지 결합 입력이다. */
record SplitMessageContext(List<String> fragments, String joinedNormalized) {
    static SplitMessageContext from(List<ChatMessage> messages) {
        List<String> fragments = messages.stream().map(ChatMessage::getContent).toList();
        String joined = fragments.stream().map(SplitMessageContext::normalize).reduce("", String::concat);
        return new SplitMessageContext(fragments, joined);
    }

    boolean containsMultipleMessages() {
        return fragments.size() > 1;
    }

    List<String> recentCanonicalCandidates() {
        int lastIndex = fragments.size();
        return IntStream.rangeClosed(2, Math.min(4, lastIndex))
                .mapToObj(size -> fragments.subList(lastIndex - size, lastIndex).stream()
                        .map(SplitMessageContext::normalize).reduce("", String::concat))
                .toList();
    }

    private static String normalize(String content) {
        String koreanOnly = content.replaceAll("[^가-힣]", "");
        return koreanOnly.replaceAll("씨이+[잉]*", "씨").replaceAll("시이+[잉]*", "시");
    }
}
