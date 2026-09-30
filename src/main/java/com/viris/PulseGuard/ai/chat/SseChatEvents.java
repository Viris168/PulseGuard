package com.viris.PulseGuard.ai.chat;

import com.viris.PulseGuard.ai.chat.dto.ChatStreamEvents;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * {@link ChatEvents} over Server-Sent Events: each event is written to the open response as
 * {@code event: <name>} and {@code data: <json>}, and flushed to the browser at once.
 */
class SseChatEvents implements ChatEvents {

    private final SseEmitter emitter;

    SseChatEvents(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void delta(ChatStreamEvents.Delta delta) throws IOException {
        send("delta", delta);
    }

    @Override
    public void done(ChatStreamEvents.Done done) throws IOException {
        send("done", done);
    }

    @Override
    public void error(ChatStreamEvents.Error error) throws IOException {
        send("error", error);
    }

    @Override
    public void close() {
        try {
            emitter.complete();
        } catch (IllegalStateException alreadyClosed) {
            // Completed already, or the connection is gone; nothing left to end.
        }
    }

    private void send(String name, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
    }
}
