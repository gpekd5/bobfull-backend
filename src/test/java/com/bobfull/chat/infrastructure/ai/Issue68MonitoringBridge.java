package com.bobfull.chat.infrastructure.ai;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

// Fake 전용 exporter. 기본 앱의 Registry나 Dashboard를 바꾸지 않고 마지막 scrape를 Context 밖에 보존한다.
public final class Issue68MonitoringBridge implements AutoCloseable {
    private final MeterRegistry registry;
    private final HttpServer server;
    private volatile String frozen;

    public Issue68MonitoringBridge(int port) throws Exception {
        // 프로젝트의 Prometheus registry는 runtimeOnly다. build dependency/Production을 변경하지 않는다.
        Class<?> config = Class.forName("io.micrometer.prometheusmetrics.PrometheusConfig");
        Class<?> implementation = Class.forName("io.micrometer.prometheusmetrics.PrometheusMeterRegistry");
        registry = (MeterRegistry) implementation.getConstructor(config).newInstance(config.getField("DEFAULT").get(null));
        registry.config().commonTags("harness_scope", "issue68_fake");
        registry.config().meterFilter(new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig existing) {
                return id.getName().equals("gen_ai.client.operation")
                        ? DistributionStatisticConfig.builder().percentilesHistogram(true).build().merge(existing) : existing;
            }
        });
        server = HttpServer.create(new InetSocketAddress(port == 0 ? "127.0.0.1" : "0.0.0.0", port), 0);
        server.createContext("/metrics", exchange -> {
            byte[] body = scrape().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
    }

    public MeterRegistry registry() { return registry; }
    public int port() { return server.getAddress().getPort(); }

    public String scrape() {
        if (frozen != null) return frozen;
        try {
            return (String) registry.getClass().getMethod("scrape").invoke(registry);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("ISSUE68_PROMETHEUS_SCRAPE_FAILED", failure);
        }
    }

    public void freeze(MeterRegistry source) {
        if (source != registry) throw new IllegalArgumentException("ISSUE68_WRONG_REGISTRY");
        frozen = scrape();
    }

    @Override
    public void close() throws IOException {
        server.stop(0);
        registry.close();
    }
}
