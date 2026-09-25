package com.agenttavern.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class TableStreamHubTest {
    @Test
    void brokenClientDoesNotMakeSendThrowOrCompleteTheErroredAsyncContext() throws Exception {
        var emitter = mock(SseEmitter.class);
        var view = mock(TableView.class);
        doThrow(new IllegalStateException("already disconnected"))
                .when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        var hub = new TableStreamHub();
        try {
            assertThatCode(() -> hub.send("hash", emitter, "table", view)).doesNotThrowAnyException();
            verify(emitter, never()).complete();
        } finally {
            hub.close();
        }
    }

    @Test
    void subscriptionsRespectLimitAndExpiredTableReleasesEntries() throws Exception {
        var hub = new TableStreamHub();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            var attempts = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 16; i++) attempts.add(pool.submit(() -> {
                start.await();
                try { hub.subscribe("same-table"); return true; }
                catch (TableSessionException limit) { return false; }
            }));
            start.countDown();
            int accepted = 0;
            for (var attempt : attempts) if (attempt.get(5, java.util.concurrent.TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(8);
            assertThat(hub.connectionCount("same-table")).isEqualTo(8);
            assertThatThrownBy(() -> hub.subscribe("same-table"))
                    .isInstanceOf(TableSessionException.class).hasMessageContaining("最多打开 8 个");
            hub.disconnect("same-table");
            assertThat(hub.connectionCount("same-table")).isZero();
            hub.subscribe("same-table");
            assertThat(hub.connectionCount("same-table")).isEqualTo(1);
        } finally { pool.shutdownNow(); hub.close(); }
    }
}
