package com.bobfull.chat.domain.entity;

/** AI 분석 요청의 최종 처리 상태다. */
public enum ModerationProcessingStatus {
    SAFE,
    FLAGGED,
    ANALYSIS_FAILED
}
