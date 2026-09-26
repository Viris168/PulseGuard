package com.viris.PulseGuard.notification;

import com.viris.PulseGuard.enumeration.ChannelType;
import com.viris.PulseGuard.enumeration.NotificationEventType;
import com.viris.PulseGuard.enumeration.NotificationStatus;
import com.viris.PulseGuard.incident.Incident;
import com.viris.PulseGuard.incident.IncidentRepository;
import com.viris.PulseGuard.incident.dto.IncidentOpenedEvent;
import com.viris.PulseGuard.incident.dto.IncidentResolvedEvent;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.notification.dto.AlertMessage;
import com.viris.PulseGuard.notification.repository.NotificationChannelRepository;
import com.viris.PulseGuard.notification.repository.NotificationRepository;
import com.viris.PulseGuard.notification.repository.NotificationSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns incident events into alerts on every enabled channel of the monitor's owner.
 *
 * <p>AFTER_COMMIT: a rolled-back incident never alerts. {@code @Async}: sending never blocks
 * the check thread. No listener-wide transaction: each channel is claim, send, finish, with
 * the claim and the finish in their own short transactions and the send in none, so no
 * database connection is held while SMTP (or later Slack) is being slow.
 *
 * <p>Claim before send: the unique {@code (incident_id, channel_id, event_type)} row is
 * inserted first, so of two concurrent deliveries exactly one wins, before anything
 * irreversible happens. At most once: a crash between claim and send leaves a visible
 * PENDING row rather than a duplicate email.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final IncidentRepository incidentRepository;
    private final NotificationChannelRepository channelRepository;
    private final NotificationRepository notificationRepository;
    private final AlertMessageFactory messages;
    private final Map<ChannelType, NotificationSender> senders;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;

    /** Spring injects every {@link NotificationSender}; a new channel type needs no change here. */
    public NotificationService(IncidentRepository incidentRepository,
                               NotificationChannelRepository channelRepository,
                               NotificationRepository notificationRepository,
                               AlertMessageFactory messages,
                               List<NotificationSender> senders,
                               PlatformTransactionManager transactionManager) {
        this.incidentRepository = incidentRepository;
        this.channelRepository = channelRepository;
        this.notificationRepository = notificationRepository;
        this.messages = messages;
        this.senders = senders.stream()
                .collect(Collectors.toMap(NotificationSender::type, Function.identity()));
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        // REQUIRES_NEW: in Postgres a failed statement poisons its whole transaction, so a
        // lost claim must not take the other channels' writes down with it.
        this.writeTx = new TransactionTemplate(transactionManager);
        this.writeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOpened(IncidentOpenedEvent event) {
        notify(event.incidentId(), NotificationEventType.OPENED);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onResolved(IncidentResolvedEvent event) {
        notify(event.incidentId(), NotificationEventType.RESOLVED);
    }

    /** What to send and where; built inside a transaction so lazy relations can load. */
    private record Plan(AlertMessage message, List<Target> targets) {
    }

    private record Target(Long channelId, ChannelType type, String address) {
    }

    void notify(Long incidentId, NotificationEventType eventType) {
        Plan plan = readTx.execute(status -> prepare(incidentId, eventType));
        if (plan == null) {
            log.debug("Incident gone before alerting; skipping incidentId={}", incidentId);
            return;
        }
        for (Target target : plan.targets()) {
            deliver(incidentId, target, eventType, plan.message());
        }
    }

    private Plan prepare(Long incidentId, NotificationEventType eventType) {
        Incident incident = incidentRepository.findById(incidentId).orElse(null);
        if (incident == null) {
            return null;
        }
        Monitor monitor = incident.getMonitor();
        AlertMessage message = eventType == NotificationEventType.OPENED
                ? messages.opened(monitor, incident)
                : messages.resolved(monitor, incident);
        List<Target> targets = channelRepository.findAllByUserIdAndEnabledTrue(monitor.getUser().getId()).stream()
                .map(channel -> new Target(channel.getId(), channel.getType(), channel.getTarget()))
                .toList();
        return new Plan(message, targets);
    }

    /** One channel failing must never stop the others, hence a try per channel, not per loop. */
    private void deliver(Long incidentId, Target target, NotificationEventType eventType, AlertMessage message) {
        NotificationSender sender = senders.get(target.type());
        if (sender == null) {
            log.warn("No sender for channel type {}; skipping channelId={}", target.type(), target.channelId());
            return;
        }
        // Cheap filter for the common case; the claim below is the actual guarantee.
        if (notificationRepository.existsByIncidentIdAndChannelIdAndEventType(incidentId, target.channelId(), eventType)) {
            log.debug("Already notified incidentId={} channelId={} event={}", incidentId, target.channelId(), eventType);
            return;
        }

        Long claimId = claim(incidentId, target.channelId(), eventType);
        if (claimId == null) {
            log.info("Alert already claimed by another delivery; skipping incidentId={} channelId={} event={}",
                    incidentId, target.channelId(), eventType);
            return;
        }

        NotificationStatus outcome;
        try {
            sender.send(target.address(), message);
            outcome = NotificationStatus.SENT;
            log.info("Sent {} alert for incidentId={} via {} channelId={}",
                    eventType, incidentId, target.type(), target.channelId());
        } catch (Exception e) {
            outcome = NotificationStatus.FAILED;
            // Never log the target, nor the exception message: for webhooks and Slack the target
            // is itself a secret URL, and HTTP client exceptions quote the request URL.
            log.error("Failed {} alert for incidentId={} via {} channelId={}: {}",
                    eventType, incidentId, target.type(), target.channelId(), e.getClass().getSimpleName());
        }
        finish(claimId, outcome);
    }

    /** Inserts the PENDING row; the unique constraint lets exactly one delivery win. Null if it lost. */
    private Long claim(Long incidentId, Long channelId, NotificationEventType eventType) {
        try {
            return writeTx.execute(status -> {
                Notification record = new Notification();
                record.setIncident(incidentRepository.getReferenceById(incidentId));
                record.setChannel(channelRepository.getReferenceById(channelId));
                record.setEventType(eventType);
                record.setStatus(NotificationStatus.PENDING);
                // saveAndFlush, not save: the duplicate must surface now, before sending.
                return notificationRepository.saveAndFlush(record).getId();
            });
        } catch (DataIntegrityViolationException e) {
            return null;
        }
    }

    private void finish(Long claimId, NotificationStatus outcome) {
        writeTx.executeWithoutResult(status -> notificationRepository.findById(claimId).ifPresent(record -> {
            record.setStatus(outcome);
            record.setSentAt(Instant.now());
        }));
    }
}
