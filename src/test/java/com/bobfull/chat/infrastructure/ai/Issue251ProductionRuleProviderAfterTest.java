package com.bobfull.chat.infrastructure.ai;

import com.bobfull.chat.application.result.AiModerationResult;
import com.bobfull.chat.application.result.ModerationResult;
import com.bobfull.chat.domain.entity.ChatMessage;
import com.bobfull.chat.domain.entity.ChatModeration;
import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.RiskLevel;
import com.bobfull.chat.application.port.AiModerationPort;
import com.bobfull.chat.infrastructure.repository.ChatMessageRepository;
import com.bobfull.chat.infrastructure.repository.ChatModerationRepository;
import com.bobfull.chat.application.service.ChatModerationService;
import com.bobfull.chat.application.exception.ModerationAnalysisException;
import com.bobfull.chat.application.service.ModerationRulePolicy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

/** Issue #251 STEP 3C: 실제 production service route를 Frozen Dataset으로 측정한다. */
@Tag("openai-evaluation")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "ISSUE251_AFTER", matches = "true")
@ActiveProfiles("local")
@ContextConfiguration(classes = Issue251ProductionRuleProviderAfterTest.CapturingConfig.class)
@SpringBootTest(properties = {
        "spring.ai.openai.chat.model=${OPENAI_EVAL_MODEL:${OPENAI_CHAT_MODEL:gpt-4o-mini}}",
        "spring.datasource.url=jdbc:h2:mem:issue251-after;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop", "jwt.secret=openai-evaluation-only-secret-key-with-minimum-length",
        "portone.api-secret=test-api-secret", "portone.store-id=test-store-id", "portone.webhook-secret=d2hzZWNfZEdWemRDMXpkR055WlhRPQ==",
        "spring.mail.host=localhost", "spring.mail.port=1025", "payment.expiration.enabled=false", "payment.refund-reconciliation.enabled=false",
        "reservation.recruitment-deadline.enabled=false", "reservation.dining-end.enabled=false", "outbox.chat-room.enabled=false", "outbox.email.enabled=false",
        "logging.level.org.hibernate.SQL=OFF", "logging.level.org.hibernate.orm.jdbc.bind=OFF"})
class Issue251ProductionRuleProviderAfterTest {
    @Autowired private ChatModerationService service;
    @Autowired private ChatMessageRepository messages;
    @Autowired private ChatModerationRepository moderations;
    @Autowired private ModerationRulePolicy rulePolicy;
    @Autowired private CapturingAiModerationPort capturingPort;

    @DynamicPropertySource static void openAiApiKey(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.api-key", () -> System.getenv("OPENAI_API_KEY"));
    }

    @Test
    void Frozen_v1을_production_CLEAR_FLAGGED_Rule로_실제_측정한다() {
        Metrics metrics = new Metrics();
        for (Issue251HardeningDataset.SingleMessageCase c : Issue251HardeningDataset.singleMessageCases()) {
            Observation o = analyze(c.caseId(), c.input(), metrics);
            metrics.add(c.proposedModerationResult(), c.proposedCategories(), c.proposedRisk(), o);
            if ("PROMPT_INJECTION".equals(c.type())) metrics.addInjection(c, o);
            if ("OBFUSCATION".equals(c.type())) metrics.addObfuscation(c, o);
        }
        for (Issue251HardeningDataset.SplitSequenceCase c : Issue251HardeningDataset.splitSequenceCases()) {
            List<Observation> fragments = new ArrayList<>();
            for (Issue251HardeningDataset.SplitMessage m : c.messages()) fragments.add(analyze(c.caseId(), m.content(), metrics));
            Observation aggregate = aggregate(fragments);
            metrics.add(c.proposedFinalModerationResult(), c.proposedCategories(), c.proposedRisk(), aggregate);
            metrics.addSplit(c.proposedFinalModerationResult(), aggregate.result);
        }
        metrics.print();
        assertThat(metrics.fastPathFalsePositive).isZero();
        assertThat(metrics.fastPathOpenAiCalls).isZero();
    }

    private Observation analyze(String caseId, String input, Metrics metrics) {
        boolean fastPath = rulePolicy.clearFlagged(input).isPresent();
        ChatMessage message = messages.saveAndFlush(ChatMessage.create(1L, 2L, 3L, input));
        long started = System.nanoTime();
        try { service.analyze(message.getId()); }
        catch (ModerationAnalysisException exception) {
            AiModerationResult raw = capturingPort.responses.get(input);
            metrics.applicationValidationFailures++;
            System.out.printf("[251-AFTER] case=%s input=%s route=LLM_REQUIRED providerRaw=%s applicationValidation=FAIL(%s)%n", caseId, quote(input), raw == null ? "NOT_CAPTURED" : raw.result(), exception.getMessage());
            return new Observation(ModerationResultType.FLAGGED, EnumSet.noneOf(ModerationCategory.class), RiskLevel.MEDIUM, false, false, elapsed(started));
        }
        ChatModeration saved = moderations.findByMessageId(message.getId()).orElseThrow();
        boolean openAi = "OpenAI".equals(saved.getProvider());
        metrics.addTraversal(saved, openAi, fastPath);
        System.out.printf("[251-AFTER] case=%s input=%s route=%s stored=%s/%s/%s openAiCalled=%s tokens=%s/%s/%s latencyMs=%d%n",
                caseId, quote(input), fastPath ? "CLEAR_FLAGGED" : "LLM_REQUIRED", saved.getResult(), saved.getCategories(), saved.getRiskLevel(), openAi,
                saved.getPromptTokens(), saved.getCompletionTokens(), saved.getTotalTokens(), elapsed(started));
        return new Observation(saved.getResult(), saved.getCategories(), saved.getRiskLevel(), fastPath, openAi, elapsed(started));
    }
    private static Observation aggregate(List<Observation> list) {
        var cats = EnumSet.noneOf(ModerationCategory.class); RiskLevel risk = RiskLevel.LOW; boolean flagged = false;
        for (Observation o : list) if (o.result == ModerationResultType.FLAGGED) { flagged = true; cats.addAll(o.categories); if (o.risk.ordinal() > risk.ordinal()) risk = o.risk; }
        return flagged ? new Observation(ModerationResultType.FLAGGED, cats, risk, false, false, 0) : new Observation(ModerationResultType.SAFE, EnumSet.noneOf(ModerationCategory.class), RiskLevel.LOW, false, false, 0);
    }
    private static String quote(String v) { return '"' + v.replace("\n", "\\n") + '"'; }
    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    private record Observation(ModerationResultType result, java.util.Set<ModerationCategory> categories, RiskLevel risk, boolean fastPath, boolean openAi, long latency) { }

    @TestConfiguration static class CapturingConfig {
        @Bean @Primary CapturingAiModerationPort capturingAiModerationPort(SpringAiModerationAdapter delegate) { return new CapturingAiModerationPort(delegate); }
    }
    static class CapturingAiModerationPort implements AiModerationPort {
        final AiModerationPort delegate; final Map<String, AiModerationResult> responses = new ConcurrentHashMap<>();
        CapturingAiModerationPort(AiModerationPort delegate) { this.delegate = delegate; }
        public AiModerationResult analyze(String content) { AiModerationResult response = delegate.analyze(content); responses.put(content, response); return response; }
    }
    static class Metrics {
        int total, exactResult, exactCategory, exactRisk, tp, fp, fn, tn, fastPath, fastPathCorrect, fastPathFalsePositive, fastPathOpenAiCalls, injectionSecurityDetermined, injectionSecurityPass, injectionSecurityNotDeterminable, obfuscationTotal, obfuscationDetected, splitFlagged, splitDetected, splitFp, splitFn, applicationValidationFailures;
        long calls, prompt, completion, tokens; final List<Long> latencies = new ArrayList<>(), ruleLatencies = new ArrayList<>(), llmLatencies = new ArrayList<>(); final java.util.Set<String> actualModels = new java.util.TreeSet<>();
        void addTraversal(ChatModeration saved, boolean openAi, boolean fast) { if (fast) fastPath++; if (openAi) { calls++; prompt += n(saved.getPromptTokens()); completion += n(saved.getCompletionTokens()); tokens += n(saved.getTotalTokens()); actualModels.add(saved.getModel()); } if (fast && openAi) fastPathOpenAiCalls++; latencies.add(saved.getLatencyMillis()); (fast ? ruleLatencies : llmLatencies).add(saved.getLatencyMillis()); }
        void add(ModerationResultType e, java.util.Set<ModerationCategory> c, RiskLevel r, Observation o) { total++; exactResult += e == o.result ? 1 : 0; exactCategory += c.equals(o.categories) ? 1 : 0; exactRisk += r == o.risk ? 1 : 0; boolean ef=e==ModerationResultType.FLAGGED, af=o.result==ModerationResultType.FLAGGED; if(ef&&af)tp++;else if(!ef&&af)fp++;else if(ef)fn++;else tn++; if(o.fastPath){if(ef&&c.equals(o.categories)&&r==o.risk)fastPathCorrect++; else if(!ef)fastPathFalsePositive++;}}
        void addInjection(Issue251HardeningDataset.SingleMessageCase c, Observation o) { Issue251InjectionSecurityMetrics.SecurityEvaluation evaluation=Issue251InjectionSecurityMetrics.evaluate(c,o.result); if(evaluation==Issue251InjectionSecurityMetrics.SecurityEvaluation.PASS){injectionSecurityDetermined++;injectionSecurityPass++;}else if(evaluation==Issue251InjectionSecurityMetrics.SecurityEvaluation.FAIL){injectionSecurityDetermined++;}else injectionSecurityNotDeterminable++; }
        void addObfuscation(Issue251HardeningDataset.SingleMessageCase c, Observation o) { obfuscationTotal++; if(o.result==c.proposedModerationResult()&&o.categories.equals(c.proposedCategories())) obfuscationDetected++; }
        void addSplit(ModerationResultType e, ModerationResultType a) { if(e==ModerationResultType.FLAGGED){splitFlagged++;if(a==ModerationResultType.FLAGGED)splitDetected++;else splitFn++;}else if(a==ModerationResultType.FLAGGED)splitFp++; }
        void print(){double p=(tp+fp)==0?0:(double)tp/(tp+fp), r=(tp+fn)==0?0:(double)tp/(tp+fn), f=p+r==0?0:2*p*r/(p+r);System.out.printf("[251-AFTER-SUMMARY] cases=%d result=%d/%d category=%d/%d risk=%d/%d TP=%d FP=%d FN=%d TN=%d precision=%.3f recall=%.3f f1=%.3f fastPath=%d fastPathPrecision=%.3f fastPathFP=%d llmCalls=%d prompt=%d completion=%d totalTokens=%d actualModels=%s injectionSecurity=%d/%d injectionSecurityNotDeterminable=%d obfuscation=%d/%d split=%d/%d splitFP=%d splitFN=%d applicationValidationFailures=%d latencyAvg=%.1f latencyP50=%d latencyP95=%d ruleLatencyAvg=%.1f llmLatencyAvg=%.1f%n",total,exactResult,total,exactCategory,total,exactRisk,total,tp,fp,fn,tn,p,r,f,fastPath,fastPath==0?0:(double)fastPathCorrect/fastPath,fastPathFalsePositive,calls,prompt,completion,tokens,actualModels,injectionSecurityPass,injectionSecurityDetermined,injectionSecurityNotDeterminable,obfuscationDetected,obfuscationTotal,splitDetected,splitFlagged,splitFp,splitFn,applicationValidationFailures,avg(latencies),pct(latencies,.5),pct(latencies,.95),avg(ruleLatencies),avg(llmLatencies));}
        static double avg(List<Long> values){return values.stream().mapToLong(Long::longValue).average().orElse(0);} 
        static long pct(List<Long> values,double q){var s=values.stream().sorted().toList();return s.isEmpty()?0:s.get(Math.min(s.size()-1,(int)Math.ceil(q*s.size())-1));}
        long pct(double q){var s=latencies.stream().sorted().toList();return s.isEmpty()?0:s.get(Math.min(s.size()-1,(int)Math.ceil(q*s.size())-1));} static long n(Long v){return v==null?0:v;}
    }
}
