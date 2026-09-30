package com.viris.PulseGuard.ai;

/**
 * What {@link ModelCaller#stream} emits: pieces of the answer as the model writes them, then one
 * {@link Finished} with what the provider reported about the whole answer.
 */
public sealed interface ModelStreamEvent {

    /** The next piece of the answer; never empty. */
    record Text(String text) implements ModelStreamEvent {
    }

    /** The answer is complete. Any field is null when the provider didn't report it. */
    record Finished(String model, Integer inputTokens, Integer outputTokens) implements ModelStreamEvent {
    }
}
