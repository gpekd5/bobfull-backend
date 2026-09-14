package com.bobfull.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Issue251HardeningDatasetTest {
    @Test
    void DRAFT_Dataset을_읽으면_caseId와_필수_필드가_유효하다() {
        var singles = Issue251HardeningDataset.singleMessageCases();
        var splits = Issue251HardeningDataset.splitSequenceCases();
        Set<String> ids = new HashSet<>();
        singles.forEach(c -> {
            assertThat(ids.add(c.caseId())).isTrue();
            assertThat(c.type()).isNotBlank(); assertThat(c.input()).isNotBlank();
            assertThat(c.proposedModerationResult()).isNotNull(); assertThat(c.proposedCategories()).isNotNull();
            assertThat(c.proposedRisk()).isNotNull(); assertThat(c.note()).isNotBlank();
            if (c.type().equals("PROMPT_INJECTION")) assertThat(c.expectedInstructionFollowed()).isFalse();
        });
        splits.forEach(c -> {
            assertThat(ids.add(c.caseId())).isTrue();
            assertThat(c.messages()).hasSizeGreaterThanOrEqualTo(2);
            assertThat(c.messages()).allSatisfy(m -> { assertThat(m.offsetMs()).isGreaterThanOrEqualTo(0); assertThat(m.content()).isNotBlank(); });
            assertThat(c.proposedFinalModerationResult()).isNotNull(); assertThat(c.proposedCategories()).isNotNull();
            assertThat(c.proposedRisk()).isNotNull(); assertThat(c.note()).isNotBlank();
        });
        assertThat(singles).hasSize(52); assertThat(splits).hasSize(14); assertThat(ids).hasSize(66);
    }

    @Test
    void DRAFT_Dataset에는_same_sender_same_room과_세가지_Context_비결합_Control이_있다() {
        var splits = Issue251HardeningDataset.splitSequenceCases();
        assertThat(Issue251HardeningDataset.HUMAN_LABEL_STATUS).isEqualTo("CONFIRMED");
        assertThat(Issue251HardeningDataset.CONTEXT_WINDOW_MILLIS).isEqualTo(30_000L);
        assertThat(splits).anyMatch(c -> c.caseId().equals("SPLIT-01") && c.contextExpectation() == Issue251HardeningDataset.ContextExpectation.REQUIRED);
        assertThat(splits).anyMatch(c -> c.caseId().equals("SPLIT-09") && c.contextExpectation() == Issue251HardeningDataset.ContextExpectation.FORBIDDEN && !c.senderKey().equals(c.lastMessageSenderKey()));
        assertThat(splits).anyMatch(c -> c.caseId().equals("SPLIT-10") && c.contextExpectation() == Issue251HardeningDataset.ContextExpectation.FORBIDDEN && !c.roomKey().equals(c.lastMessageRoomKey()));
        assertThat(splits).anyMatch(c -> c.caseId().equals("SPLIT-11") && c.contextExpectation() == Issue251HardeningDataset.ContextExpectation.FORBIDDEN && c.note().contains("window"));
    }

    @Test
    void DRAFT_Dataset_해시는_canonical_직렬화로_재현된다() throws NoSuchAlgorithmException {
        String hash = datasetContentSha256();
        System.out.printf("[251-HARDENING-DATASET] version=%s sha256=%s%n", Issue251HardeningDataset.VERSION, hash);
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    }

    static String datasetContentSha256() throws NoSuchAlgorithmException {
        StringBuilder canonical = new StringBuilder(Issue251HardeningDataset.VERSION).append('\n');
        Issue251HardeningDataset.singleMessageCases().forEach(c -> canonical.append(c.caseId()).append('|').append(c.type()).append('|')
                .append(c.input()).append('|').append(c.proposedModerationResult()).append('|').append(c.proposedCategories()).append('|')
                .append(c.proposedRisk()).append('|').append(c.expectedSchemaValid()).append('|').append(c.expectedInstructionFollowed()).append('|')
                .append(c.humanReviewRequired()).append('|').append(c.note()).append('\n'));
        Issue251HardeningDataset.splitSequenceCases().forEach(c -> canonical.append(c.caseId()).append('|').append(c.type()).append('|')
                .append(c.roomKey()).append('|').append(c.senderKey()).append('|').append(c.lastMessageRoomKey()).append('|').append(c.lastMessageSenderKey()).append('|')
                .append(c.messages()).append('|').append(c.contextExpectation()).append('|')
                .append(c.proposedFinalModerationResult()).append('|').append(c.proposedCategories()).append('|').append(c.proposedRisk()).append('|')
                .append(c.humanReviewRequired()).append('|').append(c.note()).append('\n'));
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte value : hash) result.append(String.format("%02x", value));
        return result.toString();
    }
}
