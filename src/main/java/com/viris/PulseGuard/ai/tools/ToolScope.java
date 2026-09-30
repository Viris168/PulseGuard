package com.viris.PulseGuard.ai.tools;

import org.springframework.ai.chat.model.ToolContext;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;

/**
 * What one question's tools may see, decided by the server when the message arrives: who is
 * asking, which of their monitors they shared with Ask AI, their time zone, and how far back
 * their plan keeps history. It reaches the tools through Spring AI's {@link ToolContext}, never
 * through the model's arguments, so the model can choose <em>what</em> to look up but never
 * <em>whose</em> data.
 *
 * @param historyDays the plan's history (Free 7, Pro 90, Business 365); older days are out of reach
 */
public record ToolScope(Long userId, Set<Long> sharedMonitorIds, ZoneId zone, int historyDays, Instant now) {

    static final String CONTEXT_KEY = "pulseguard.toolScope";

    public ToolScope {
        sharedMonitorIds = Set.copyOf(sharedMonitorIds);
    }

    public boolean canSee(Long monitorId) {
        return sharedMonitorIds.contains(monitorId);
    }

    /** Today in the user's time zone: "last month" means their month. */
    public LocalDate today() {
        return now.atZone(zone).toLocalDate();
    }

    /** The oldest day the plan still keeps. */
    public LocalDate earliestDay() {
        return today().minusDays(historyDays);
    }

    /** For {@code ToolCallingChatOptions.toolContext(...)}. */
    public Map<String, Object> asToolContext() {
        return Map.of(CONTEXT_KEY, this);
    }

    /** The scope a tool runs under. A tool called without one is a bug, never "no restriction". */
    public static ToolScope from(ToolContext context) {
        Object scope = context == null ? null : context.getContext().get(CONTEXT_KEY);
        if (scope instanceof ToolScope s) {
            return s;
        }
        throw new IllegalStateException("Tool called without a ToolScope");
    }
}
