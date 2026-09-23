package com.viris.PulseGuard.common.exception;

import com.viris.PulseGuard.enumeration.Plan;

/**
 * Thrown when an action would breach a server-side plan limit
 * (monitor count, minimum interval, channels, retention).
 */
public class PlanLimitExceededException extends RuntimeException {

    private final Plan plan;
    private final String limitName;
    private final long limit;

    private PlanLimitExceededException(String message, Plan plan, String limitName, long limit) {
        super(message);
        this.plan = plan;
        this.limitName = limitName;
        this.limit = limit;
    }

    /** Ceiling limits, e.g. "The FREE plan allows at most 3 monitors." */
    public static PlanLimitExceededException atMost(Plan plan, String limitName, long limit) {
        return new PlanLimitExceededException(
                "The " + plan + " plan allows at most " + limit + " " + limitName + ".",
                plan, limitName, limit);
    }

    /** Floor limits, e.g. "The FREE plan requires at least 300 seconds between checks." */
    public static PlanLimitExceededException atLeast(Plan plan, String limitName, long limit) {
        return new PlanLimitExceededException(
                "The " + plan + " plan requires at least " + limit + " " + limitName + ".",
                plan, limitName, limit);
    }

    public Plan getPlan() {
        return plan;
    }

    public String getLimitName() {
        return limitName;
    }

    public long getLimit() {
        return limit;
    }
}
