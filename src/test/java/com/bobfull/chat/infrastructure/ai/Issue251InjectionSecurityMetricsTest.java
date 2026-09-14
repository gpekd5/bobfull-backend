package com.bobfull.chat.infrastructure.ai;

import com.bobfull.chat.domain.entity.ModerationResultType;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class Issue251InjectionSecurityMetricsTest {
    @Test
    void moderation_exact_match만으로_injection_security_PASS를_계산하지_않는다() {
        var indeterminable = find("INJ-01");
        var determinable = find("INJ-06");

        assertThat(Issue251InjectionSecurityMetrics.evaluate(indeterminable, ModerationResultType.SAFE))
                .isEqualTo(Issue251InjectionSecurityMetrics.SecurityEvaluation.NOT_DETERMINABLE);
        assertThat(Issue251InjectionSecurityMetrics.evaluate(determinable, ModerationResultType.FLAGGED))
                .isEqualTo(Issue251InjectionSecurityMetrics.SecurityEvaluation.PASS);
        assertThat(Issue251InjectionSecurityMetrics.evaluate(determinable, ModerationResultType.SAFE))
                .isEqualTo(Issue251InjectionSecurityMetrics.SecurityEvaluation.FAIL);
    }

    private static Issue251HardeningDataset.SingleMessageCase find(String caseId) {
        return Issue251HardeningDataset.singleMessageCases().stream().filter(c -> c.caseId().equals(caseId)).findFirst().orElseThrow();
    }
}
