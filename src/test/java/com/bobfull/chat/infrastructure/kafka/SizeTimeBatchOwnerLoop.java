package com.bobfull.chat.infrastructure.kafka;

import com.bobfull.chat.application.event.ChatMessageCreatedEvent;
import com.bobfull.chat.application.service.ChatModerationBatchService;
import com.bobfull.chat.application.service.ChatModerationBatchService.PreparedItem;
import com.bobfull.chat.infrastructure.metrics.ChatModerationBatchMetrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.listener.DefaultErrorHandler;

// 실험 전용. Worker/Timer 없이 Consumer 생성부터 close까지 한 owner thread가 실행한다.
final class SizeTimeBatchOwnerLoop implements Runnable {
    static final int BUFFER_LIMIT = 20;
    private final Supplier<Consumer<Object, Object>> consumerSupplier;
    private final String topic;
    private final ChatModerationBatchService service;
    private final ChatModerationBatchRetryProcessor retry;
    private final DefaultErrorHandler indexedRetry;
    private final ChatModerationBatchMetrics metrics;
    private final long maxWaitNs;
    private final long backoffNs;
    private final List<Entry> buffer = new ArrayList<>();
    final List<Flush> flushes = new CopyOnWriteArrayList<>();
    final List<Commit> commits = new CopyOnWriteArrayList<>();
    final List<Integer> pollSizes = new CopyOnWriteArrayList<>();
    volatile boolean stop;
    volatile Throwable failure;
    volatile int buffered, maxBuffered, assigned, revokeCount, lostCount, pausedPolls, retryCount, threadViolations;
    volatile boolean rebalanceRequested, injectLostNotification;
    volatile long ownerThreadId;
    // 장애 주입도 owner thread에서만 실행한다. Consumer 자체를 외부에 공개하지 않는다.
    java.util.function.Consumer<Map<TopicPartition, OffsetAndMetadata>> beforeCommit = offsets -> {};
    java.util.function.Consumer<List<ConsumerRecord<Object, Object>>> beforeExecution = records -> {};
    java.util.function.Consumer<List<ConsumerRecord<Object, Object>>> afterExecution = records -> {};
    private Consumer<Object, Object> consumer;
    private ConsumerRebalanceListener lifecycle;
    private boolean inWork;

    SizeTimeBatchOwnerLoop(Supplier<Consumer<Object, Object>> consumerSupplier, String topic,
            ChatModerationBatchService service, ChatModerationBatchRetryProcessor retry,
            DefaultErrorHandler indexedRetry, ChatModerationBatchMetrics metrics, long maxWaitMs, long backoffMs) {
        this.consumerSupplier = consumerSupplier;
        this.topic = topic;
        this.service = service;
        this.retry = retry;
        this.indexedRetry = indexedRetry;
        this.metrics = metrics;
        this.maxWaitNs = Duration.ofMillis(maxWaitMs).toNanos();
        this.backoffNs = Duration.ofMillis(backoffMs).toNanos();
    }

    @Override
    public void run() {
        ownerThreadId = Thread.currentThread().getId();
        try (Consumer<Object, Object> owned = threadChecked(consumerSupplier.get())) {
            consumer = owned;
            lifecycle = new ConsumerRebalanceListener() {
                @Override
                public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                    revokeCount++;
                    commitReady();
                    discard(partitions);
                }

                @Override
                public void onPartitionsLost(Collection<TopicPartition> partitions) {
                    lostCount++;
                    discard(partitions);
                }

                @Override
                public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                    assigned = owned.assignment().size();
                    if (inWork) owned.pause(partitions);
                }
            };
            owned.subscribe(List.of(topic), lifecycle);
            while (!stop) {
                retry.pruneCommitted(owned);
                commitReady();
                List<Entry> pending = buffer.stream().filter(entry -> !entry.done).toList();
                if (!pending.isEmpty()) {
                    long left = pending.get(0).readyNs + maxWaitNs - System.nanoTime();
                    String reason = pending.stream().anyMatch(entry -> entry.item.failure() != null) ? "preparation_failure"
                            : pending.size() >= 10 ? "size" : buffer.size() > BUFFER_LIMIT - 10 ? "capacity"
                            : left <= 0 ? "deadline" : null;
                    if (reason != null) {
                        process(pending.subList(0, Math.min(10, pending.size())), reason);
                        continue;
                    }
                    poll(Math.min(Duration.ofMillis(100).toNanos(), left));
                } else {
                    if (buffer.size() > BUFFER_LIMIT - 10) {
                        consumer.pause(consumer.assignment());
                        pollPaused();
                    } else {
                        poll(Duration.ofMillis(100).toNanos());
                    }
                }
            }
            // 새 Provider flush 없이 완료한 연속 prefix만 commit하고 미완료는 Broker 재전달에 맡긴다.
            commitReady();
        } catch (Throwable exception) {
            failure = exception;
        } finally {
            buffer.clear();
            buffered = 0;
            assigned = 0;
            retry.clearThreadState();
            indexedRetry.clearThreadState();
        }
    }

    private void poll(long timeoutNs) {
        if (buffer.size() > BUFFER_LIMIT - 10) {
            throw new IllegalStateException("BUFFER_POLL_RESERVATION_VIOLATION");
        }
        consumer.resume(consumer.assignment());
        var records = consumer.poll(Duration.ofNanos(Math.max(0, timeoutNs)));
        if (!records.isEmpty()) {
            metrics.poll(records.count());
            pollSizes.add(records.count());
        }
        if (records.count() > 10 || buffer.size() + records.count() > BUFFER_LIMIT) {
            throw new IllegalStateException("BUFFER_LIMIT_VIOLATION");
        }
        List<ConsumerRecord<Object, Object>> input = new ArrayList<>();
        records.forEach(input::add);
        for (var record : input) {
            if (!(record.value() instanceof ChatMessageCreatedEvent event) || event.eventVersion() != 1 || event.messageId() == null) {
                throw new IllegalStateException("INVALID_EXPERIMENT_EVENT");
            }
        }
        var prepared = service.prepare(input.stream().map(SizeTimeBatchOwnerLoop::messageId).toList());
        for (int i = 0; i < input.size(); i++) {
            buffer.add(new Entry(input.get(i), prepared.get(i), System.nanoTime()));
        }
        buffered = buffer.size();
        maxBuffered = Math.max(maxBuffered, buffered);
        if (rebalanceRequested) {
            rebalanceRequested = false;
            consumer.enforceRebalance("issue65-owner-lifecycle-test");
        }
        if (injectLostNotification) {
            injectLostNotification = false;
            lifecycle.onPartitionsLost(List.copyOf(consumer.assignment()));
            stop = true;
        }
    }

    private void process(List<Entry> selected, String reason) {
        var work = List.copyOf(selected);
        var records = work.stream().map(entry -> entry.record).toList();
        long selectedAt = System.nanoTime();
        flushes.add(new Flush(reason, work.size(), work.stream().map(entry -> selectedAt - entry.readyNs).toList()));
        inWork = true;
        consumer.pause(consumer.assignment());
        try {
            while (!stop && buffer.containsAll(work)) {
                try {
                    beforeExecution.accept(records);
                    retry.analyze(records, ids -> {
                        List<PreparedItem> items = new ArrayList<>();
                        for (Long id : ids) {
                            Entry entry = work.stream().filter(value -> messageId(value.record).equals(id)).findFirst().orElseThrow();
                            if (entry.executions++ > 0 && entry.item.failure() != null) {
                                entry.item = service.prepare(List.of(id)).get(0);
                            }
                            items.add(entry.item);
                        }
                        service.analyzePrepared(items);
                    });
                    afterExecution.accept(records);
                    work.forEach(entry -> entry.done = true);
                    return;
                } catch (ChatModerationBatchRetryProcessor.GroupRetryException exception) {
                    retryCount++;
                    pausedBackoff();
                } catch (BatchListenerFailedException exception) {
                    int index = exception.getIndex();
                    if (index < 0 || index >= records.size()) throw exception;
                    retryCount++;
                    // 예전 poll index를 handleBatch에 넘기지 않고 해당 record에 기존 tracker/분류/복구만 적용한다.
                    indexedRetry.handleOne(exception, records.get(index), consumer, null);
                    pollPaused();
                }
            }
        } finally {
            inWork = false;
            consumer.resume(consumer.assignment());
        }
    }

    private void pausedBackoff() {
        long deadline = System.nanoTime() + backoffNs;
        do {
            if (stop) return;
            pollPaused();
        } while (System.nanoTime() < deadline);
    }

    private void pollPaused() {
        var records = consumer.poll(Duration.ofMillis(10));
        pausedPolls++;
        if (!records.isEmpty()) throw new IllegalStateException("PAUSED_POLL_RETURNED_RECORDS");
    }

    private void commitReady() {
        Map<TopicPartition, OffsetAndMetadata> offsets = new LinkedHashMap<>();
        Map<TopicPartition, Boolean> blocked = new LinkedHashMap<>();
        for (Entry entry : buffer) {
            TopicPartition partition = partition(entry.record);
            if (!entry.done) blocked.put(partition, true);
            if (!blocked.getOrDefault(partition, false)) {
                offsets.put(partition, new OffsetAndMetadata(entry.record.offset() + 1));
            }
        }
        offsets.keySet().retainAll(consumer.assignment());
        if (offsets.isEmpty()) return;
        try {
            beforeCommit.accept(Map.copyOf(offsets));
            consumer.commitSync(offsets);
            commits.add(new Commit(System.nanoTime(), offsets.values().stream().mapToLong(OffsetAndMetadata::offset).sum()));
            buffer.removeIf(entry -> offsets.containsKey(partition(entry.record))
                    && entry.record.offset() < offsets.get(partition(entry.record)).offset());
            buffered = buffer.size();
        } catch (org.apache.kafka.common.KafkaException exception) {
            // DB 성공을 취소하지 않고 미commit 참조를 보존한다. 재전달 완료 Skip은 별도로 검증한다.
        }
    }

    private void discard(Collection<TopicPartition> partitions) {
        buffer.removeIf(entry -> partitions.contains(partition(entry.record)));
        buffered = buffer.size();
        retry.revoke(partitions);
        // indexed tracker의 thread 전체 정리는 다른 partition의 상태도 버리므로 종료 시에만 수행한다.
    }

    private static TopicPartition partition(ConsumerRecord<?, ?> record) {
        return new TopicPartition(record.topic(), record.partition());
    }

    private static Long messageId(ConsumerRecord<?, ?> record) {
        return ((ChatMessageCreatedEvent) record.value()).messageId();
    }

    @SuppressWarnings("unchecked")
    private Consumer<Object, Object> threadChecked(Consumer<Object, Object> delegate) {
        return (Consumer<Object, Object>) java.lang.reflect.Proxy.newProxyInstance(Consumer.class.getClassLoader(),
                new Class<?>[] {Consumer.class}, (proxy, method, args) -> {
                    if (Thread.currentThread().getId() != ownerThreadId) {
                        threadViolations++;
                        throw new IllegalStateException("CONSUMER_THREAD_OWNERSHIP_VIOLATION");
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (java.lang.reflect.InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    record Flush(String reason, int size, List<Long> waitsNs) { }
    record Commit(long atNs, long nextOffsetSum) { }

    private static final class Entry {
        final ConsumerRecord<Object, Object> record;
        PreparedItem item;
        final long readyNs;
        int executions;
        boolean done;

        Entry(ConsumerRecord<Object, Object> record, PreparedItem item, long readyNs) {
            this.record = record;
            this.item = item;
            this.readyNs = readyNs;
            this.done = !item.requiresProvider() && item.failure() == null;
        }
    }
}
