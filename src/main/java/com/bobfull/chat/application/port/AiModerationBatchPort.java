package com.bobfull.chat.application.port;

import com.bobfull.chat.application.result.ModerationResult;
import java.util.List;

// 중립 ID로 Batch 응답을 매핑한다. 요청 전체 Usage를 메시지별 결과에 복제하지 않는다.
public interface AiModerationBatchPort {
    BatchResult analyze(List<Item> items);

    record Item(String id, String content) {
    }

    record Output(String id, ModerationResult moderation) {
    }

    record BatchResult(List<Output> results, String provider, String model) {
    }
}
