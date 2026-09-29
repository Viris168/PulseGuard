package com.viris.PulseGuard.billing;

import com.viris.PulseGuard.enumeration.ChannelType;
import com.viris.PulseGuard.enumeration.Plan;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Server-side plan gating (PRD 2.7). Services must consult this before creating
 * monitors or channels — never trust the client.
 */
@Component
public class PlanLimits {

    /** Sentinel for the Business tier's unlimited monitor count. */
    public static final int UNLIMITED = Integer.MAX_VALUE;

    public record Limits(
            int maxMonitors,
            int minIntervalSeconds,
            int retentionDays,
            Set<ChannelType> channels,
            // Ask AI questions per day; UNLIMITED still meets AiProperties' fair-use cap.
            int aiDailyQuestions
    ) {}

    private static final Map<Plan, Limits> LIMITS = Map.of(
            Plan.FREE, new Limits(3, 300, 7, Set.of(ChannelType.EMAIL), 5),
            Plan.PRO, new Limits(25, 60, 90, Set.of(ChannelType.EMAIL, ChannelType.SLACK), 100),
            Plan.BUSINESS, new Limits(UNLIMITED, 60, 365,
                    // No SMS until a sender exists: a plan must never sell a channel that stays silent.
                    Set.of(ChannelType.EMAIL, ChannelType.SLACK), UNLIMITED)
    );

    public Limits forPlan(Plan plan) {
        Limits limits = LIMITS.get(plan);
        if (limits == null) {
            throw new IllegalStateException("No limits configured for plan " + plan);
        }
        return limits;
    }

    public int maxMonitors(Plan plan) {
        return forPlan(plan).maxMonitors();
    }

    public int minIntervalSeconds(Plan plan) {
        return forPlan(plan).minIntervalSeconds();
    }

    public int retentionDays(Plan plan) {
        return forPlan(plan).retentionDays();
    }

    public int aiDailyQuestions(Plan plan) {
        return forPlan(plan).aiDailyQuestions();
    }

    public boolean allowsChannel(Plan plan, ChannelType type) {
        return forPlan(plan).channels().contains(type);
    }
}
