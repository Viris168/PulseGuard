package com.viris.PulseGuard.ai;

import com.viris.PulseGuard.ai.dto.IncidentSummaryResponse;
import com.viris.PulseGuard.enumeration.IncidentStatus;
import com.viris.PulseGuard.incident.Incident;
import com.viris.PulseGuard.incident.IncidentQueryService;
import com.viris.PulseGuard.incident.IncidentRepository;
import com.viris.PulseGuard.incident.dto.IncidentDetailResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * A plain-English summary of one incident, written by the configured chat model (AI_PLAN.md,
 * milestone 0). Cached on the incident: a resolved incident is summarised once, an open one at
 * most once per {@code pulseguard.ai.open-summary-ttl}, so page views don't each cost a call.
 *
 * <p>Never fails the page: with no model configured, or when the provider errors or times out,
 * the result is empty and the frontend shows its own rule-based summary instead.
 *
 * <p>Not {@code @Transactional}: the model call can take seconds and must not hold a
 * connection. The ownership check and the cache write each run in their own short transaction.
 */
@Service
public class IncidentSummaryService {

    /** Bounds what is stored and shown if the model ignores its length instructions. */
    static final int MAX_SUMMARY_LENGTH = 1200;

    private final IncidentQueryService incidentQueryService;
    private final IncidentRepository incidentRepository;
    private final ModelCaller modelCaller;
    private final AiProperties properties;
    private final Clock clock;

    @Autowired
    public IncidentSummaryService(IncidentQueryService incidentQueryService,
                                  IncidentRepository incidentRepository,
                                  ModelCaller modelCaller,
                                  AiProperties properties) {
        this(incidentQueryService, incidentRepository, modelCaller, properties, Clock.systemUTC());
    }

    IncidentSummaryService(IncidentQueryService incidentQueryService,
                           IncidentRepository incidentRepository,
                           ModelCaller modelCaller,
                           AiProperties properties,
                           Clock clock) {
        this.incidentQueryService = incidentQueryService;
        this.incidentRepository = incidentRepository;
        this.modelCaller = modelCaller;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The caller's incident summarised, or empty when AI is off or failed. Another tenant's
     * incident throws {@code IncidentNotFoundException}, exactly like a missing one, before
     * anything is sent to the provider.
     */
    public Optional<IncidentSummaryResponse> summarize(Long userId, Long incidentId) {
        IncidentDetailResponse incident = incidentQueryService.detail(userId, incidentId);
        Instant now = clock.instant();

        Optional<IncidentSummaryResponse> cached = incidentRepository.findById(incidentId)
                .filter(i -> isFresh(i, now))
                .map(i -> new IncidentSummaryResponse(i.getAiSummary(), i.getAiSummaryAt()));
        if (cached.isPresent()) {
            return cached;
        }

        Optional<String> summary = modelCaller
                .call(IncidentSummaryPrompt.build(incident, now), "summary incidentId=" + incidentId)
                .map(IncidentSummaryService::bounded);
        summary.ifPresent(text -> incidentRepository.saveAiSummary(incidentId, text, now));
        return summary.map(text -> new IncidentSummaryResponse(text, now));
    }

    /** Resolved: written after the resolution. Open: younger than the TTL. */
    boolean isFresh(Incident incident, Instant now) {
        Instant at = incident.getAiSummaryAt();
        if (incident.getAiSummary() == null || at == null) {
            return false;
        }
        if (incident.getStatus() == IncidentStatus.RESOLVED) {
            return incident.getResolvedAt() != null && !at.isBefore(incident.getResolvedAt());
        }
        return at.plus(properties.openSummaryTtl()).isAfter(now);
    }

    private static String bounded(String text) {
        return text.length() <= MAX_SUMMARY_LENGTH ? text : text.substring(0, MAX_SUMMARY_LENGTH) + "…";
    }
}
