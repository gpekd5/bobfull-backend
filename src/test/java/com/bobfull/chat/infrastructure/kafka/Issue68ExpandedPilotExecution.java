package com.bobfull.chat.infrastructure.kafka;

import com.bobfull.chat.infrastructure.ai.Issue68ExpandedLiveSession;
import com.bobfull.chat.infrastructure.ai.Issue68ExpandedPlan;
import com.bobfull.chat.infrastructure.ai.Issue68HttpBoundary;
import com.bobfull.chat.infrastructure.ai.Issue68SdkUsage;
import java.util.Map;

// Live와 loopback rehearsal이 같은 실제 Consumer/JPA/Owner 완료 판정을 사용한다.
final class Issue68ExpandedPilotExecution {
    private Issue68ExpandedPilotExecution() { }

    static Map<String, Object> execute(String bootstrap, String jdbc, Issue68ExpandedLiveSession session,
            Issue68ExpandedPlan.Unit unit, Issue68HttpBoundary boundary, String endpoint,
            java.util.function.Consumer<SizeTimeBatchOwnerLoop> ownerSetup) {
        var sdk = new Issue68SdkUsage(boundary);
        var result = Issue68OpenAiKafkaHarness.executeExpanded(bootstrap, jdbc, "root", "issue68-fixture-only",
                unit, boundary, endpoint, context -> context.getBean(io.micrometer.observation.ObservationRegistry.class)
                        .observationConfig().observationHandler(sdk), ownerSetup);
        boolean technical = result.finalRows() == 20 && result.finalLag() == 0 && result.dltRecords() == 0
                && result.saveFailures() == 0 && result.transactionViolations() == 0 && result.unsafeCommits() == 0
                && result.commitObservations() > 0 && result.samples().stream().allMatch(s -> s.completed()
                        && s.committedNs() > 0 && s.committedNs() <= s.returnedNs())
                && !boundary.calls().isEmpty() && boundary.calls().stream().allMatch(c -> sdk.matches(c.number))
                && boundary.attempts().stream().allMatch(a -> a.status() == 200 && a.settlement().equals("SETTLED"))
                && result.gateReasons().stream().noneMatch(reason -> reason.equals("USAGE_UNKNOWN")
                        || reason.equals("DB_OFFSET_BOUNDARY") || reason.equals("RESPONSE_MODEL_CHANGED")
                        || reason.equals("TECHNICAL_FAILURE_OBSERVED") || reason.startsWith("LAG_OBSERVATION_FAILED"));
        var report = Map.<String, Object>of("unit", unit, "result", result, "logical", boundary.calls(),
                "http", boundary.attempts(), "sdk", sdk.entries(), "v1v2", session.plan().evaluations(),
                "executionHolds", session.plan().holds(), "technicalOk", technical);
        session.checkpoint("unit-result", report); // completeUnit 전에 관측 결과를 영속 보존한다.
        if (!technical) session.plan().stop();
        session.plan().completeUnit(technical); // HOLD면 다음 조건과 다음 HTTP로 넘어가지 않는다.
        return report;
    }
}
