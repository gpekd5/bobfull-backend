package com.bobfull.chat.infrastructure.ai;

import com.openai.models.completions.CompletionUsage;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.ai.chat.observation.ChatModelObservationContext;

// SDK가 파싱한 요청별 Usage를 HTTP 복사본과 대조한다. 응답 원문/인증정보는 보관하지 않는다.
public final class Issue68SdkUsage implements ObservationHandler<ChatModelObservationContext> {
    public record Entry(long logical, String model, Issue68Budget.Usage usage) { }

    private final Issue68HttpBoundary boundary;
    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    public Issue68SdkUsage(Issue68HttpBoundary boundary) {
        this.boundary = boundary;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ChatModelObservationContext;
    }

    @Override
    public void onStop(ChatModelObservationContext context) {
        if (context.getResponse() != null) {
            var metadata = context.getResponse().getMetadata();
            var usage = metadata.getUsage();
            Long cached = usage.getNativeUsage() instanceof CompletionUsage nativeUsage
                    ? nativeUsage.promptTokensDetails().flatMap(details -> details.cachedTokens()).orElse(null) : null;
            var entry = new Entry(boundary.currentLogicalNumber(), metadata.getModel(),
                    new Issue68Budget.Usage(usage.getPromptTokens().longValue(),
                            usage.getCompletionTokens().longValue(), usage.getTotalTokens().longValue(), cached));
            entries.add(entry);
            boundary.sdkUsage(entry);
        }
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public boolean matches(long logical) {
        var sdk = entries.stream().filter(e -> e.logical() == logical).toList();
        var http = boundary.attempts().stream().filter(a -> a.logical() == logical && a.status() == 200).toList();
        return sdk.size() == 1 && http.size() == 1 && http.get(0).usage() != null
                && sdk.get(0).usage().equals(http.get(0).usage());
    }
}
