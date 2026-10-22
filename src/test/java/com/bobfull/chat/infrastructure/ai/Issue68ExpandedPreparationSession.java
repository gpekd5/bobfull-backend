package com.bobfull.chat.infrastructure.ai;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

// 고정 Live root와 API Key를 받지 않는다. 유료 승인 전에는 temp/loopback만 연결할 수 있다.
public final class Issue68ExpandedPreparationSession implements AutoCloseable {
    private final Issue68ExpandedPlan plan;
    private final Issue68Budget budget;
    private final Path directory;
    private final String manifestHash;
    private final List<Issue68HttpBoundary> boundaries = new ArrayList<>();
    private int sequence;

    public Issue68ExpandedPreparationSession(Path fixtureRoot, Issue68ExpandedPlan.Stage stage) throws IOException {
        manifestHash = Issue68ExpandedPlan.manifestSha256();
        plan = new Issue68ExpandedPlan(stage);
        budget = Issue68Budget.startExpandedPreparation(fixtureRoot.resolve("budget.jsonl"), stage, manifestHash);
        try {
            directory = Files.createDirectory(fixtureRoot.resolve("expanded-fake-" + stage.name()));
            checkpoint("manifest", Issue68ExpandedPlan.manifest());
        } catch (Exception failure) {
            budget.close();
            throw failure;
        }
    }

    public Issue68ExpandedPlan plan() { return plan; }
    public Issue68Budget budget() { return budget; }

    public Issue68HttpBoundary boundary(java.net.URI endpoint) {
        Issue68HttpBoundary boundary;
        try {
            plan.requireActive();
            boundary = Issue68HttpBoundary.expandedPreparation(endpoint, budget, plan);
        } catch (RuntimeException failure) {
            plan.stop();
            throw failure;
        }
        boundary.authorization(() -> {
            try {
                if (!manifestHash.equals(Issue68ExpandedPlan.manifestSha256())) {
                    throw new IllegalStateException("ISSUE68_EXPANDED_SOURCE_CHANGED");
                }
            } catch (IOException failure) {
                throw new IllegalStateException("ISSUE68_EXPANDED_SOURCE_UNAVAILABLE");
            }
        });
        boundary.checkpoints(this::checkpoint);
        boundaries.add(boundary);
        return boundary;
    }

    public synchronized void checkpoint(String name, Object value) {
        try {
            Path file = directory.resolve("%05d-%s.json".formatted(++sequence, name));
            byte[] bytes = new JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of(
                    "scope", "FAKE_PREPARATION_NOT_LIVE", "name", name, "data", value));
            try (var channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try (var parent = FileChannel.open(directory, StandardOpenOption.READ)) { parent.force(true); }
        } catch (IOException failure) {
            plan.stop();
            throw new IllegalStateException("ISSUE68_EXPANDED_CHECKPOINT_FAILED");
        }
    }

    public void completeStage() {
        budget.completeExpandedStage(plan);
        checkpoint("stage-complete", Map.of("stage", plan.stage(), "burden", budget.chargedNanos(),
                "gross", budget.grossReservedNanos(), "attempts", budget.attempts()));
    }

    @Override
    public void close() throws IOException {
        boundaries.forEach(Issue68HttpBoundary::close);
        budget.close();
    }
}
