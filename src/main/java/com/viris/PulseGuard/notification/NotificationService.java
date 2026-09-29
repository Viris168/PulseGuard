package com.viris.PulseGuard.notification;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.billing.PlanLimits;
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
import com.viris.PulseGuard.enumeration.IncidentStatus;
import com.viris.PulseGuard.notification.channels.SlackDeliveryException;
import org.springframework.data.domain.PageRequest;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
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

import java.time.Duration;
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
    private final PlanLimits planLimits;
    private final Map<ChannelType, NotificationSender> senders;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;
    private final AlertRetryProperties retry;

    /** Retries handled per sweep; more due ones wait for the next sweep. */
    static final int RETRY_BATCH = 100;

    /**
     * A send still PENDING after this died with the app. Retried rather than left for good: if
     * the crash came after the send, the alert goes out twice, which beats never learning of
     * an outage. Sends take seconds, so this cannot catch one still in progress.
     */
    static final Duration STUCK_AFTER = Duration.ofMinutes(15);

    /** Spring injects every {@link NotificationSender}; a new channel type needs no change here. */
    public NotificationService(IncidentRepository incidentRepository,
                               NotificationChannelRepository channelRepository,
                               NotificationRepository notificationRepository,
                               AlertMessageFactory messages,
                               PlanLimits planLimits,
                               List<NotificationSender> senders,
                               PlatformTransactionManager transactionManager,
                               AlertRetryProperties retry) {
        this.incidentRepository = incidentRepository;
        this.channelRepository = channelRepository;
        this.notificationRepository = notificationRepository;
        this.messages = messages;
        this.planLimits = planLimits;
        this.senders = senders.stream()
                .collect(Collectors.toMap(NotificationSender::type, Function.identity()));
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        // REQUIRES_NEW: in Postgres a failed statement poisons its whole transaction, so a
        // lost claim must not take the other channels' writes down with it.
        this.writeTx = new TransactionTemplate(transactionManager);
        this.writeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.retry = retry;
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
        User owner = monitor.getUser();
        List<Target> targets = channelRepository.findAllByUserIdAndEnabledTrue(owner.getId()).stream()
                // Downgrades switch these off already; this is the server-side guarantee (rule 5)
                // for any row that slipped past, e.g. one enabled before plan gating existed.
                .filter(channel -> {
                    if (!channel.confirmedFor(owner)) {
                        log.info("Skipping EMAIL channelId={} for incidentId={}: address not confirmed",
                                channel.getId(), incidentId);
                        return false;
                    }
                    boolean allowed = planLimits.allowsChannel(owner.getPlan(), channel.getType());
                    if (!allowed) {
                        log.info("Skipping {} channelId={} for incidentId={}: not on the {} plan",
                                channel.getType(), channel.getId(), incidentId, owner.getPlan());
                    }
                    return allowed;
                })
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

        Exception failure = send(sender, target.address(), message);
        if (failure == null) {
            log.info("Sent {} alert for incidentId={} via {} channelId={}",
                    eventType, incidentId, target.type(), target.channelId());
        } else {
            // Never log the target, nor the exception message: for webhooks and Slack the target
            // is itself a secret URL, and HTTP client exceptions quote the request URL.
            log.error("Failed {} alert for incidentId={} via {} channelId={}: {}",
                    eventType, incidentId, target.type(), target.channelId(), failure.getClass().getSimpleName());
        }
        finish(claimId, failure);
    }

    /** @return null when sent, else the failure */
    private static Exception send(NotificationSender sender, String address, AlertMessage message) {
        try {
            sender.send(address, message);
            return null;
        } catch (Exception e) {
            return e;
        }
    }

    /**
     * Whether trying again later could help. Timeouts, refused connections and 5xx do; a
     * revoked Slack webhook or an address the mail server cannot parse never will.
     */
    static boolean retryable(Exception failure) {
        if (failure instanceof SlackDeliveryException slack) {
            return slack.isRetryable();
        }
        return !(failure instanceof MailParseException || failure instanceof MailPreparationException);
    }

    /**
     * Sends every failed alert whose retry is due. Run by AlertRetryJob. Each retry is claimed
     * first (FAILED → PENDING in one conditional UPDATE), so across nodes it is sent once.
     *
     * @return how many were attempted
     */
    public int retryDue(Instant now) {
        if (!retry.backoff().isEmpty()) {
            Integer released = writeTx.execute(status -> notificationRepository.releaseStuck(now, now.minus(STUCK_AFTER)));
            if (released != null && released > 0) {
                log.warn("Retrying {} alert(s) left PENDING by an interrupted send", released);
            }
        }
        List<Long> due = notificationRepository.findRetryDueIds(now, PageRequest.of(0, RETRY_BATCH));
        int attempted = 0;
        for (Long id : due) {
            Integer claimed = writeTx.execute(status -> notificationRepository.claimRetry(id, now));
            if (claimed == null || claimed == 0) {
                continue; // another node took it
            }
            attempted++;
            Resend resend = readTx.execute(status -> prepareResend(id));
            if (resend == null || resend.skipReason() != null) {
                log.info("Giving up alert notificationId={}: {}", id,
                        resend == null ? "notification gone" : resend.skipReason());
                finishRetry(id, false, null);
                continue;
            }
            Exception failure = send(resend.sender(), resend.address(), resend.message());
            if (failure == null) {
                log.info("Sent {} alert on retry for incidentId={} via {} channelId={}",
                        resend.eventType(), resend.incidentId(), resend.type(), resend.channelId());
            } else {
                log.warn("Retry failed for {} alert incidentId={} via {} channelId={}: {}",
                        resend.eventType(), resend.incidentId(), resend.type(), resend.channelId(),
                        failure.getClass().getSimpleName());
            }
            finishRetry(id, true, failure);
        }
        return attempted;
    }

    /** What a resend needs, or why it should not happen. */
    private record Resend(Long incidentId, Long channelId, ChannelType type, NotificationEventType eventType,
                          NotificationSender sender, String address, AlertMessage message, String skipReason) {

        static Resend skip(String reason) {
            return new Resend(null, null, null, null, null, null, null, reason);
        }
    }

    /**
     * Re-checks everything the first send checked, because time has passed: the channel may be
     * off or off-plan now, and a "down" alert for an incident that has since resolved would
     * only confuse; its "recovered" alert tells the reader what they need.
     */
    private Resend prepareResend(Long id) {
        Notification n = notificationRepository.findForRetry(id).orElse(null);
        if (n == null) {
            return null;
        }
        NotificationChannel channel = n.getChannel();
        Incident incident = n.getIncident();
        Monitor monitor = incident.getMonitor();
        User owner = monitor.getUser();
        if (!channel.isEnabled()) {
            return Resend.skip("channel disabled");
        }
        if (!planLimits.allowsChannel(owner.getPlan(), channel.getType())) {
            return Resend.skip("channel not on the " + owner.getPlan() + " plan");
        }
        if (!channel.confirmedFor(owner)) {
            return Resend.skip("address not confirmed");
        }
        NotificationSender sender = senders.get(channel.getType());
        if (sender == null) {
            return Resend.skip("no sender for " + channel.getType());
        }
        if (n.getEventType() == NotificationEventType.OPENED && incident.getStatus() == IncidentStatus.RESOLVED) {
            return Resend.skip("incident already resolved");
        }
        AlertMessage message = n.getEventType() == NotificationEventType.OPENED
                ? messages.opened(monitor, incident)
                : messages.resolved(monitor, incident);
        return new Resend(incident.getId(), channel.getId(), channel.getType(), n.getEventType(),
                sender, channel.getTarget(), message, null);
    }

    /**
     * Records a retry's outcome. {@code attempted} is false when it was given up without
     * sending; then it just ends as FAILED with nothing further due.
     */
    private void finishRetry(Long id, boolean attempted, Exception failure) {
        writeTx.executeWithoutResult(status -> notificationRepository.findById(id).ifPresent(record -> {
            Instant now = Instant.now();
            if (attempted) {
                record.setAttempts(record.getAttempts() + 1);
                record.setSentAt(now);
            }
            if (attempted && failure == null) {
                record.setStatus(NotificationStatus.SENT);
                record.setNextAttemptAt(null);
                return;
            }
            record.setStatus(NotificationStatus.FAILED);
            record.setNextAttemptAt(attempted ? nextAttempt(record.getAttempts(), failure, now) : null);
        }));
    }

    /** When to try again after the {@code attempts}-th failure, or null to give up. */
    private Instant nextAttempt(int attempts, Exception failure, Instant now) {
        if (!retryable(failure)) {
            return null;
        }
        Duration delay = retry.delayAfter(attempts);
        return delay == null ? null : now.plus(delay);
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

    /** Records the first send's outcome, scheduling a retry if it failed and one could help. */
    private void finish(Long claimId, Exception failure) {
        writeTx.executeWithoutResult(status -> notificationRepository.findById(claimId).ifPresent(record -> {
            Instant now = Instant.now();
            record.setStatus(failure == null ? NotificationStatus.SENT : NotificationStatus.FAILED);
            record.setSentAt(now);
            record.setNextAttemptAt(failure == null ? null : nextAttempt(record.getAttempts(), failure, now));
        }));
    }
}
