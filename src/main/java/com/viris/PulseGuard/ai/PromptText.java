package com.viris.PulseGuard.ai;

/**
 * Makes recorded text (monitor names, error messages from monitored servers) safe to place
 * inside a prompt's data section: one line, so it can't forge a line of the facts, and no
 * angle brackets, so it can't close the section's tag and speak as the prompt itself.
 */
public final class PromptText {

    private PromptText() {
    }

    public static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").replace("<", "‹").replace(">", "›").strip();
    }

    /**
     * Keeps line breaks (a chat answer's bullet list), but no angle brackets, so the text can't
     * open or close a tag such as {@code <data>}. For people's own questions and earlier answers.
     */
    public static String noTags(String text) {
        return text == null ? "" : text.replace("<", "‹").replace(">", "›").strip();
    }

    /** {@link #oneLine}, cut to {@code max} characters. */
    public static String clip(String text, int max) {
        String line = oneLine(text);
        return line.length() <= max ? line : line.substring(0, max) + "…";
    }
}
