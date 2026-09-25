package com.agenttavern.web;

import jakarta.annotation.PreDestroy;
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
        heartbeat.scheduleAtFixedRate(() -> connections.forEach((hash, list) -> list.forEach(emitter -> {
            try { emitter.send(SseEmitter.event().comment("heartbeat")); }
            catch (Exception error) { remove(hash, emitter); }
        })), 15, 15, TimeUnit.SECONDS);
    }
    SseEmitter subscribe(String hash) {
        var emitter = new SseEmitter(30 * 60_000L);
        connections.compute(hash, (key, existing) -> {
            var list = existing == null ? new CopyOnWriteArrayList<SseEmitter>() : existing;
            if (list.size() >= 8) throw new TableSessionException(429, "同一牌桌最多打开 8 个实时窗口");
            list.add(emitter);
            return list;
        });
        Runnable remove = () -> remove(hash, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        return emitter;
    }
    private void remove(String hash, SseEmitter emitter) {
        connections.computeIfPresent(hash, (key, list) -> {
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
    }
    void send(String hash, SseEmitter emitter, String name, TableView view) {
        try { emitter.send(SseEmitter.event().id(Long.toString(view.sequence())).name(name).data(view)); }
        catch (Exception error) { remove(hash, emitter); }
    }
    void publish(String hash, TableView view) {
        var list = connections.get(hash);
        if (list != null) list.forEach(emitter -> send(hash, emitter, "table", view));
    }
    int connectionCount(String hash) {
        var list = connections.get(hash);
        return list == null ? 0 : list.size();
    }
    void disconnect(String hash) {
        var list = connections.remove(hash);
        if (list != null) list.forEach(emitter -> {
            try { emitter.complete(); } catch (RuntimeException ignored) { /* Already disconnected. */ }
        });
    }
    @PreDestroy void close() {
        heartbeat.shutdownNow();
        connections.values().forEach(list -> list.forEach(emitter -> {
            try { emitter.complete(); } catch (RuntimeException ignored) { /* Already disconnected. */ }
        }));
    }
}
