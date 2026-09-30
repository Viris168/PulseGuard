package com.viris.PulseGuard.ai.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The tool calls of one question: how many were made (for the cap) and what each did (for the
 * audit log). Every tool the model may use is wrapped by {@link #guard}, so no call can skip it.
 */
public final class ToolRun {

    private static final Logger log = LoggerFactory.getLogger(ToolRun.class);

    private final int maxCalls;
    private final Duration timeout;
    private final ExecutorService executor;
    private volatile Consumer<ToolCallRecord> onCall;
    private final AtomicInteger started = new AtomicInteger();
    private final List<ToolCallRecord> records = new CopyOnWriteArrayList<>();

    ToolRun(int maxCalls, Duration timeout, ExecutorService executor, Consumer<ToolCallRecord> onCall) {
        this.maxCalls = maxCalls;
        this.timeout = timeout;
        this.executor = executor;
        this.onCall = onCall;
    }

    /** The tools to hand the model, each wrapped by this run's rules. */
    public List<ToolCallback> guard(List<ToolCallback> tools) {
        return tools.stream().<ToolCallback>map(t -> new GuardedToolCallback(t, this)).toList();
    }

    /** Who hears about each call as it finishes; set when the answer starts streaming. */
    public void listen(Consumer<ToolCallRecord> onCall) {
        this.onCall = onCall;
    }

    /** Every call so far, refused ones included, in the order they finished. */
    public List<ToolCallRecord> records() {
        return List.copyOf(records);
    }

    /** Counts a call; false once the cap is reached. */
    boolean tryStart() {
        return started.getAndIncrement() < maxCalls;
    }

    Duration timeout() {
        return timeout;
    }

    ExecutorService executor() {
        return executor;
    }

    void record(ToolCallRecord record) {
        records.add(record);
        try {
            onCall.accept(record);
        } catch (RuntimeException e) {
            // Showing progress must never break the answer.
            log.warn("Tool progress listener failed: {}", e.getClass().getSimpleName());
        }
    }
}
