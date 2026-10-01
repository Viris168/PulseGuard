package com.viris.PulseGuard.common.exception;

/** A help article slug that isn't one of the docs in {@code src/main/resources/help/}. */
public class HelpArticleNotFoundException extends RuntimeException {
    public HelpArticleNotFoundException(String slug) {
        super("Help article not found: " + slug);
    }
}
