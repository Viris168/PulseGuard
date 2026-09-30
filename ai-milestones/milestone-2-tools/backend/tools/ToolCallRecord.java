package com.viris.PulseGuard.ai.tools;

/**
 * One tool call, for the audit log saved with the answer and for the "Checked …" line the user
 * sees. {@code result} is a preview: what matters for debugging a wrong answer, not all of it.
 *
 * @param ok false when the call was refused (limit), failed or timed out
 */
public record ToolCallRecord(String tool, String arguments, String result, boolean ok, long durationMs) {
}
