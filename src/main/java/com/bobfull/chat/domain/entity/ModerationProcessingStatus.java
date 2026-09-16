package com.bobfull.chat.domain.entity;

// AI 분석의 안전·차단·최종 실패 상태를 표현한다.
public enum ModerationProcessingStatus {
    SAFE,
    FLAGGED,
    ANALYSIS_FAILED
}
