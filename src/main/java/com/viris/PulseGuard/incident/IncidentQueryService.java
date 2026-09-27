package com.viris.PulseGuard.incident;

import com.viris.PulseGuard.check.Check;
import com.viris.PulseGuard.check.CheckRepository;
import com.viris.PulseGuard.common.exception.IncidentNotFoundException;
import com.viris.PulseGuard.enumeration.IncidentStatus;
import com.viris.PulseGuard.incident.dto.IncidentDetailResponse;
import com.viris.PulseGuard.incident.dto.IncidentResponse;
import com.viris.PulseGuard.notification.Notification;
import com.viris.PulseGuard.notification.repository.NotificationRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Read side of incidents for the dashboard. Writing incidents stays in {@link IncidentEngine};
 * nothing here changes state.
 */
@Service
public class IncidentQueryService {

    /** Bounds the rows read for one timeline; ~8 hours of checks at a 60s interval. */
    static final int MAX_TIMELINE_CHECKS = 500;

    private final IncidentRepository incidentRepository;
    private final CheckRepository checkRepository;
    private final NotificationRepository notificationRepository;
    private final IncidentTimelineBuilder timelineBuilder;

    public IncidentQueryService(IncidentRepository incidentRepository,
                                CheckRepository checkRepository,
                                NotificationRepository notificationRepository,
                                IncidentTimelineBuilder timelineBuilder) {
        this.incidentRepository = incidentRepository;
        this.checkRepository = checkRepository;
        this.notificationRepository = notificationRepository;
        this.timelineBuilder = timelineBuilder;
    }

    /** Newest first. Scoped to the user in the query itself, so filters can never widen it. */
    @Transactional(readOnly = true)
    public List<IncidentResponse> list(Long userId, IncidentStatus status, Long monitorId, int limit) {
        return incidentRepository.search(userId, status, monitorId, PageRequest.of(0, limit)).stream()
                .map(IncidentResponse::from)
                .toList();
    }

    /**
     * One incident with its timeline. Looked up by id and owner together, so another tenant's
     * incident is a 404 exactly like a missing one. An open incident's story runs up to now.
     */
    @Transactional(readOnly = true)
    public IncidentDetailResponse detail(Long userId, Long incidentId) {
        Incident incident = incidentRepository.findByIdAndMonitorUserId(incidentId, userId)
                .orElseThrow(() -> new IncidentNotFoundException(incidentId));
        Instant end = incident.getResolvedAt() != null ? incident.getResolvedAt() : Instant.now();
        List<Check> checks = checkRepository.findByMonitorIdAndCheckedAtBetweenOrderByCheckedAtAsc(
                incident.getMonitor().getId(), incident.getStartedAt(), end,
                PageRequest.of(0, MAX_TIMELINE_CHECKS));
        List<Notification> notifications = notificationRepository.findTimelineByIncidentId(incidentId);
        return IncidentDetailResponse.from(incident, timelineBuilder.build(incident, checks, notifications));
    }
}
