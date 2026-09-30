package com.viris.PulseGuard.ai;

import com.viris.PulseGuard.ai.dto.AiQuotaResponse;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.billing.PlanLimits;
import com.viris.PulseGuard.common.exception.AiRuleException;
import com.viris.PulseGuard.enumeration.Plan;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Ask AI's daily question limit per plan, shared by one-off questions and chat messages so both
 * count the same way: Free 5, Pro 100, Business "unlimited" up to the fair-use cap.
 */
@Component
public class AiQuotaPolicy {

    private final AiQuestionQuota quota;
    private final PlanLimits planLimits;
    private final UserRepository users;
    private final AiProperties properties;

    public AiQuotaPolicy(AiQuestionQuota quota, PlanLimits planLimits, UserRepository users, AiProperties properties) {
        this.quota = quota;
        this.planLimits = planLimits;
        this.users = users;
        this.properties = properties;
    }

    /** Counts one question, or throws 429 with a message naming the plan's limit. */
    public void acquire(Long userId) {
        Plan plan = plan(userId);
        int limit = Math.min(planLimits.aiDailyQuestions(plan), properties.fairUseDailyQuestions());
        if (!quota.tryAcquire(userId, limit)) {
            throw AiRuleException.quotaReached(message(plan));
        }
    }

    /** Hands back a question that got no answer. */
    public void release(Long userId) {
        quota.release(userId);
    }

    /** Questions used today and the limit to show; null limit for unlimited plans. */
    public AiQuotaResponse status(Long userId) {
        int limit = planLimits.aiDailyQuestions(plan(userId));
        return new AiQuotaResponse(quota.used(userId), limit == PlanLimits.UNLIMITED ? null : limit);
    }

    private Plan plan(Long userId) {
        return users.findById(userId).orElseThrow().getPlan();
    }

    private String message(Plan plan) {
        int limit = planLimits.aiDailyQuestions(plan);
        if (limit == PlanLimits.UNLIMITED) {
            return "You've reached today's fair-use limit of " + properties.fairUseDailyQuestions()
                    + " questions. It resets at midnight UTC.";
        }
        String name = plan.name().charAt(0) + plan.name().substring(1).toLowerCase(Locale.ROOT);
        return "You've used all " + limit + " questions for today on the " + name
                + " plan. It resets at midnight UTC.";
    }
}
