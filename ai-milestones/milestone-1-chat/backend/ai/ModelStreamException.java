package com.viris.PulseGuard.ai;

/**
 * Ends a {@link ModelCaller#stream} that couldn't finish. Its message is safe to show and log:
 * the provider's own error text, which can echo the prompt, is never kept.
 */
public class ModelStreamException extends RuntimeException {

    private final boolean timedOut;

    private ModelStreamException(String message, boolean timedOut) {
        super(message, null, false, false);
        this.timedOut = timedOut;
    }

    public static ModelStreamException notConfigured() {
        return new ModelStreamException("No AI model is configured", false);
    }

    public static ModelStreamException timedOut() {
        return new ModelStreamException("The AI model took too long to answer", true);
    }

    public static ModelStreamException failed() {
        return new ModelStreamException("The AI model failed to answer", false);
    }

    public boolean isTimedOut() {
        return timedOut;
    }
}
