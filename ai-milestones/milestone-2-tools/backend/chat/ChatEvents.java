package com.viris.PulseGuard.ai.chat;

import com.viris.PulseGuard.ai.chat.dto.ChatStreamEvents;

import java.io.IOException;

/**
 * Where a {@link ChatTurn} sends its events. In the app it's the browser's SSE connection
 * ({@code SseChatEvents}); in tests, a fake that can pretend the browser went away. A send that
 * throws means the person is gone: they pressed Stop or closed the tab.
 */
public interface ChatEvents {

    void delta(ChatStreamEvents.Delta delta) throws IOException;

    void tool(ChatStreamEvents.Tool tool) throws IOException;

    void done(ChatStreamEvents.Done done) throws IOException;

    void error(ChatStreamEvents.Error error) throws IOException;

    /** Ends the response. Safe to call more than once. */
    void close();
}
