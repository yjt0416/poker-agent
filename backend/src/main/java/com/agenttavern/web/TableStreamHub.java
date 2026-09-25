package com.agenttavern.web;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Only receives already-committed, audience-specific projections. */
@Component
class TableStreamHub {
    private final ConcurrentMap<String, CopyOnWriteArrayList<SseEmitter>> connections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("table-heartbeat").factory());
    TableStreamHub() {
        heartbeat.scheduleAtFixedRate(() -> connections.values().forEach(list -> list.forEach(emitter -> {
            try { emitter.send(SseEmitter.event().comment("heartbeat")); }
            catch (Exception error) { list.remove(emitter); }
        })), 15, 15, TimeUnit.SECONDS);
    }
    SseEmitter subscribe(String hash) {
        var list = connections.computeIfAbsent(hash, ignored -> new CopyOnWriteArrayList<>());
        if (list.size() >= 8) throw new TableSessionException(429, "同一牌桌最多打开 8 个实时窗口");
        var emitter = new SseEmitter(30 * 60_000L);
        list.add(emitter);
        Runnable remove = () -> { list.remove(emitter); };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        return emitter;
    }
    void send(SseEmitter emitter, String name, TableView view) {
        try { emitter.send(SseEmitter.event().id(Long.toString(view.sequence())).name(name).data(view)); }
        catch (Exception error) { connections.values().forEach(list -> list.remove(emitter)); }
    }
    void publish(String hash, TableView view) {
        connections.getOrDefault(hash, new CopyOnWriteArrayList<>()).forEach(emitter -> send(emitter, "table", view));
    }
    @PreDestroy void close() {
        heartbeat.shutdownNow();
        connections.values().forEach(list -> list.forEach(emitter -> {
            try { emitter.complete(); } catch (RuntimeException ignored) { /* Already disconnected. */ }
        }));
    }
}
