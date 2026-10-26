package com.bobfull.chat.infrastructure.kafka;

import com.bobfull.chat.infrastructure.ai.Issue68A1N1Plan;
import com.bobfull.chat.infrastructure.ai.Issue68A1N1Session;
import com.bobfull.chat.infrastructure.ai.Issue68HttpBoundary;
import com.bobfull.chat.infrastructure.ai.Issue68SdkUsage;

// Fake 리허설과 향후 별도 승인된 Live가 동일 Native Consumer/Adapter/JPA 실행기를 사용한다.
final class Issue68A1N1Execution {
    private Issue68A1N1Execution() { }

    static void execute(String bootstrap, String jdbc, Issue68A1N1Session session, Issue68HttpBoundary boundary,
            String endpoint, java.util.function.Consumer<org.springframework.boot.test.context.assertj.AssertableApplicationContext> setup) {
        try {
            var sdk = new Issue68SdkUsage(boundary);
            var result = Issue68OpenAiKafkaHarness.execute(bootstrap, jdbc, "root", "issue68-fixture-only",
                    false, Issue68A1N1Plan.cases(), true, boundary, endpoint, context -> {
                        context.getBean(io.micrometer.observation.ObservationRegistry.class).observationConfig().observationHandler(sdk);
                        setup.accept(context);
                        observeCommits(context, boundary);
                    }, 1);
            session.checkpoint("n-one-result", result);
            boolean technical = result.finalRows() == 8 && result.finalLag() == 0 && result.dltRecords() == 0
                    && result.saveFailures() == 0 && result.transactionViolations() == 0 && result.unsafeCommits() == 0
                    && result.commitObservations() > 0 && result.samples().stream().allMatch(s -> s.completed()
                    && s.receivedNs() > 0 && s.committedNs() >= s.receivedNs() && s.returnedNs() >= s.committedNs())
                    && boundary.calls().size() == 8 && boundary.calls().stream().allMatch(c -> sdk.matches(c.number))
                    && boundary.attempts().stream().allMatch(a -> a.settlement().equals("SETTLED"))
                    && session.plan().complete();
            if (!technical) throw new IllegalStateException("ISSUE68_A1_N1_TECHNICAL_HOLD");
            session.complete();
        } catch (RuntimeException failure) {
            session.plan().stop();
            throw failure;
        } finally {
            session.checkpoint("n-one-final", java.util.Map.of("logical", boundary.calls(), "http", boundary.attempts(),
                    "sdk", boundary.sdkEntries(), "v1v2", session.plan().evaluations(), "executionHolds", session.plan().holds(),
                    "candidateAdoption", false, "automaticNextStage", false));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void observeCommits(org.springframework.boot.test.context.assertj.AssertableApplicationContext context,
            Issue68HttpBoundary boundary) {
        var factory = (org.springframework.kafka.core.DefaultKafkaConsumerFactory) context
                .getBean(org.springframework.kafka.core.ConsumerFactory.class);
        factory.addPostProcessor(consumer -> {
            var proxy = new org.springframework.aop.framework.ProxyFactory(consumer);
            proxy.addAdvice((org.aopalliance.intercept.MethodInterceptor) call -> {
                boolean commit = call.getMethod().getName().equals("commitSync") || call.getMethod().getName().equals("commitAsync");
                if (!commit) return call.proceed();
                var args = call.getArguments();
                var types = call.getMethod().getParameterTypes();
                if (types.length > 0 && types[types.length - 1] == org.apache.kafka.clients.consumer.OffsetCommitCallback.class) {
                    var callback = (org.apache.kafka.clients.consumer.OffsetCommitCallback) args[args.length - 1];
                    args[args.length - 1] = (org.apache.kafka.clients.consumer.OffsetCommitCallback) (offsets, failure) -> {
                        if (failure != null) commitFailed(boundary, failure);
                        if (callback != null) callback.onComplete(offsets, failure);
                    };
                }
                try { return call.proceed(); }
                catch (Throwable failure) { commitFailed(boundary, failure); throw failure; }
            });
            return (org.apache.kafka.clients.consumer.Consumer) proxy.getProxy();
        });
    }

    private static void commitFailed(Issue68HttpBoundary boundary, Throwable failure) {
        boundary.stop();
        boundary.checkpoint("offset-commit-failed", java.util.Map.of("errorClass", failure.getClass().getSimpleName()));
    }
}
