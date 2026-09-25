package com.agenttavern.web;

import static org.assertj.core.api.Assertions.assertThatCode;
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
            assertThatCode(() -> hub.send(emitter, "table", view)).doesNotThrowAnyException();
            verify(emitter, never()).complete();
        } finally {
            hub.close();
        }
    }
}
