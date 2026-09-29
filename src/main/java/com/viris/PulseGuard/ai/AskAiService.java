package com.viris.PulseGuard.ai;

import com.viris.PulseGuard.ai.dto.AiAccessDto;
import com.viris.PulseGuard.ai.dto.AiQuotaResponse;
import com.viris.PulseGuard.ai.dto.AskAiResponse;
import com.viris.PulseGuard.ai.dto.AskAiResponse.AiLink;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.billing.PlanLimits;
import com.viris.PulseGuard.common.exception.AiRuleException;
import com.viris.PulseGuard.enumeration.Plan;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Ask AI (AI_PLAN.md, option B): one question, one answer from the configured model, grounded in
 * a snapshot of the caller's own monitoring data. Nothing is sent to the provider unless the user
 * turned Ask AI on, and then only the monitors they chose.
 *
 * <p>{@link #ask} is not {@code @Transactional}: the model call can take seconds and must not
 * hold a connection. The snapshot is read in its own short read-only transaction.
 */
@Service
public class AskAiService {

    /** Bounds what is shown if the model ignores its length instructions. */
    static final int MAX_ANSWER_LENGTH = 2000;
    static final int MAX_LINKS = 3;
    private static final ZoneId UTC = ZoneId.of("UTC");

    private final AiAccessRepository accessRepository;
    private final MonitorRepository monitorRepository;
    private final UserRepository userRepository;
    private final AskAiSnapshotLoader snapshotLoader;
    private final AiQuestionQuota quota;
    private final PlanLimits planLimits;
    private final ModelCaller modelCaller;
    private final AiProperties properties;

    public AskAiService(AiAccessRepository accessRepository, MonitorRepository monitorRepository,
                        UserRepository userRepository, AskAiSnapshotLoader snapshotLoader, AiQuestionQuota quota,
                        PlanLimits planLimits, ModelCaller modelCaller, AiProperties properties) {
        this.accessRepository = accessRepository;
        this.monitorRepository = monitorRepository;
        this.userRepository = userRepository;
        this.snapshotLoader = snapshotLoader;
        this.quota = quota;
        this.planLimits = planLimits;
        this.modelCaller = modelCaller;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public AiAccessDto access(Long userId) {
        return toDto(accessRepository.findById(userId).orElseGet(() -> new AiAccess(userId)));
    }

    /**
     * Saves the user's consent. Monitor ids are kept only if the user owns them, so the list
     * can never name another tenant's monitor.
     */
    @Transactional
    public AiAccessDto saveAccess(Long userId, AiAccessDto request) {
        Set<Long> owned = monitorRepository.findAllByUserId(userId).stream()
                .map(Monitor::getId).collect(Collectors.toSet());
        Set<Long> chosen = request.monitorIds().stream().filter(owned::contains).collect(Collectors.toSet());
        if (request.enabled() && !request.allMonitors() && chosen.isEmpty()) {
            throw AiRuleException.noMonitorSelected();
        }
        AiAccess access = accessRepository.findById(userId).orElseGet(() -> new AiAccess(userId));
        access.setEnabled(request.enabled());
        access.setAllMonitors(request.allMonitors());
        access.getMonitorIds().clear();
        access.getMonitorIds().addAll(chosen);
        access.setUpdatedAt(Instant.now());
        return toDto(accessRepository.save(access));
    }

    public AiQuotaResponse quota(Long userId) {
        return new AiQuotaResponse(quota.used(userId), shownLimit(plan(userId)));
    }

    /**
     * Order matters: consent first (nothing counted, nothing sent), then the question is
     * counted, and handed back if the model gives no answer.
     */
    public AskAiResponse ask(Long userId, String question, String timeZone) {
        AiAccess access = accessRepository.findById(userId).filter(AiAccess::isEnabled)
                .orElseThrow(AiRuleException::disabled);
        if (!modelCaller.isAvailable()) {
            throw AiRuleException.notConfigured();
        }
        Plan plan = plan(userId);
        if (!quota.tryAcquire(userId, enforcedLimit(plan))) {
            throw AiRuleException.quotaReached(quotaMessage(plan));
        }

        AskAiSnapshot snapshot;
        Optional<String> reply;
        try {
            snapshot = snapshotLoader.load(userId, access, zone(timeZone));
            reply = modelCaller.call(AskAiPrompt.build(question, snapshot), "ask userId=" + userId);
        } catch (RuntimeException e) {
            quota.release(userId);
            throw e;
        }
        if (reply.isEmpty()) {
            quota.release(userId);
            throw AiRuleException.unavailable();
        }
        String answer = bounded(reply.get());
        return new AskAiResponse(answer, links(answer, snapshot),
                new AiQuotaResponse(quota.used(userId), shownLimit(plan)));
    }

    /** Links are built here from monitors the answer names, never taken from the model's text. */
    static List<AiLink> links(String answer, AskAiSnapshot snapshot) {
        String text = answer.toLowerCase(Locale.ROOT);
        return snapshot.monitors().stream()
                .filter(m -> text.contains(m.name().toLowerCase(Locale.ROOT)))
                .limit(MAX_LINKS)
                .map(m -> new AiLink("Open " + m.name(), "/monitors/" + m.id()))
                .toList();
    }

    private Plan plan(Long userId) {
        return userRepository.findById(userId).orElseThrow().getPlan();
    }

    private int enforcedLimit(Plan plan) {
        return Math.min(planLimits.aiDailyQuestions(plan), properties.fairUseDailyQuestions());
    }

    private Integer shownLimit(Plan plan) {
        int limit = planLimits.aiDailyQuestions(plan);
        return limit == PlanLimits.UNLIMITED ? null : limit;
    }

    private String quotaMessage(Plan plan) {
        int limit = planLimits.aiDailyQuestions(plan);
        if (limit == PlanLimits.UNLIMITED) {
            return "You've reached today's fair-use limit of " + properties.fairUseDailyQuestions()
                    + " questions. It resets at midnight UTC.";
        }
        String name = plan.name().charAt(0) + plan.name().substring(1).toLowerCase(Locale.ROOT);
        return "You've used all " + limit + " questions for today on the " + name
                + " plan. It resets at midnight UTC.";
    }

    private static ZoneId zone(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) {
            return UTC;
        }
        try {
            return ZoneId.of(timeZone);
        } catch (DateTimeException e) {
            return UTC;
        }
    }

    private static String bounded(String text) {
        return text.length() <= MAX_ANSWER_LENGTH ? text : text.substring(0, MAX_ANSWER_LENGTH) + "…";
    }

    private static AiAccessDto toDto(AiAccess access) {
        return new AiAccessDto(access.isEnabled(), access.isAllMonitors(),
                access.getMonitorIds().stream().sorted().toList());
    }
}
