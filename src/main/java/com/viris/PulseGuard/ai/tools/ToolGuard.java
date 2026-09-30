package com.viris.PulseGuard.ai.tools;

import com.viris.PulseGuard.ai.AiProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/** Starts a {@link ToolRun} for each question, with the configured cap and timeout. */
@Component
public class ToolGuard {

    private final AiProperties properties;
    private final ExecutorService executor;

    public ToolGuard(AiProperties properties, @Qualifier("aiCallExecutor") ExecutorService executor) {
        this.properties = properties;
        this.executor = executor;
    }

    /** A run nobody listens to yet; see {@link ToolRun#listen}. */
    public ToolRun start() {
        return start(record -> { });
    }

    /** @param onCall told about each call as it finishes, e.g. to show "Checked …" in the chat */
    public ToolRun start(Consumer<ToolCallRecord> onCall) {
        return new ToolRun(properties.maxToolCalls(), properties.toolTimeout(), executor, onCall);
    }
}
