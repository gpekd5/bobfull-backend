package com.bobfull.chat.infrastructure.ai;

import com.bobfull.chat.application.result.ModerationResult;
import java.util.List;
import java.util.Map;

// O0와 확장 Fake 리허설이 동일 HTTP/Usage 차단 경계를 사용한다. Production 계약이 아니다.
interface Issue68ExecutionGate {
    void begin(List<String> ids, boolean batch);
    void committed(Issue68EvaluationContract.Case c, ModerationResult actual, boolean committed);
    Map<String, List<String>> holds();
    void requireActive();
    void stop();
    boolean stopped();
}
