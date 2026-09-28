package com.viris.PulseGuard.notification;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.auth.jwt.UserMapper;
import com.viris.PulseGuard.billing.PlanLimits;
import com.viris.PulseGuard.common.exception.ChannelNotFoundException;
import com.viris.PulseGuard.common.exception.ChannelRuleException;
import com.viris.PulseGuard.common.exception.InvalidChannelTargetException;
import com.viris.PulseGuard.common.exception.PlanLimitExceededException;
import com.viris.PulseGuard.common.exception.TooManyAttemptsException;
import com.viris.PulseGuard.enumeration.ChannelType;
import com.viris.PulseGuard.enumeration.Plan;
import com.viris.PulseGuard.notification.dto.ChannelRequest;
import com.viris.PulseGuard.notification.dto.ChannelResponse;
import com.viris.PulseGuard.notification.repository.NotificationChannelRepository;
import com.viris.PulseGuard.notification.repository.NotificationSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Alert channel CRUD. Every lookup is scoped by the caller's id, so another tenant's channel is
 * a 404. Plan gating runs here, never only in the UI: adding or re-enabling a channel type the
 * plan does not include is a 403. Disabling or deleting is always allowed.
 */
@Service
public class ChannelService {

    private static final Logger log = LoggerFactory.getLogger(ChannelService.class);

    /** Every enabled channel is alerted per incident, so the list is bounded on every plan. */
    static final int MAX_CHANNELS = 20;

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    // Pinned to Slack's host: a free-form URL here would be an SSRF target on every alert.
    private static final Pattern SLACK = Pattern.compile("^https://hooks\\.slack\\.com/services/\\S+$");
    private static final Pattern PHONE = Pattern.compile("^\\+[1-9]\\d{7,14}$");
    private static final Pattern PHONE_SEPARATORS = Pattern.compile("[\\s-]");

    private final NotificationChannelRepository channelRepository;
    private final UserRepository userRepository;
    private final PlanLimits planLimits;
    private final AlertMessageFactory messages;
    private final Map<ChannelType, NotificationSender> senders;
    private final ChannelConfirmationService confirmations;
    private final TestAlertThrottle testThrottle;

    public ChannelService(NotificationChannelRepository channelRepository,
                          UserRepository userRepository,
                          PlanLimits planLimits,
                          AlertMessageFactory messages,
                          List<NotificationSender> senders,
                          TestAlertThrottle testThrottle,
                          ChannelConfirmationService confirmations) {
        this.channelRepository = channelRepository;
        this.userRepository = userRepository;
        this.planLimits = planLimits;
        this.messages = messages;
        this.senders = senders.stream()
                .collect(Collectors.toMap(NotificationSender::type, Function.identity()));
        this.testThrottle = testThrottle;
        this.confirmations = confirmations;
    }

    @Transactional(readOnly = true)
    public List<ChannelResponse> listChannels(Long userId) {
        User owner = requireUser(userId);
        return channelRepository.findAllByUserIdOrderByIdAsc(userId).stream()
                .map(channel -> ChannelResponse.from(channel, owner))
                .toList();
    }

    @Transactional
    public ChannelResponse createChannel(Long userId, ChannelRequest request) {
        User user = requireUser(userId);
        requireAllowedType(user.getPlan(), request.type());

        String target = normalize(request.type(), request.target());
        if (channelRepository.countByUserId(userId) >= MAX_CHANNELS) {
            throw PlanLimitExceededException.atMost(user.getPlan(), "alert channels", MAX_CHANNELS);
        }
        if (channelRepository.existsByUserIdAndTypeAndTarget(userId, request.type(), target)) {
            throw ChannelRuleException.duplicate();
        }

        NotificationChannel channel = new NotificationChannel();
        channel.setUser(user);
        channel.setType(request.type());
        channel.setTarget(target);
        channelRepository.save(channel);
        // Anyone else's address has to say yes before alerts go there.
        if (!channel.confirmedFor(user)) {
            confirmations.request(user, target);
        }

        log.info("Created {} channelId={} for userId={}", channel.getType(), channel.getId(), userId);
        return ChannelResponse.from(channel, user);
    }

    @Transactional
    public ChannelResponse setEnabled(Long userId, Long channelId, boolean enabled) {
        NotificationChannel channel = requireOwnedChannel(userId, channelId);
        if (enabled) {
            // A channel kept after a downgrade stays switched off until the plan covers it again.
            requireAllowedType(channel.getUser().getPlan(), channel.getType());
        }
        channel.setEnabled(enabled);
        log.info("{} channelId={} for userId={}", enabled ? "Enabled" : "Disabled", channelId, userId);
        return ChannelResponse.from(channel, channel.getUser());
    }

    /** Sends the confirmation link again, for an email channel still waiting on it. */
    @Transactional
    public void resendConfirmation(Long userId, Long channelId) {
        NotificationChannel channel = requireOwnedChannel(userId, channelId);
        User owner = requireUser(userId);
        if (channel.confirmedFor(owner) || channel.getTarget().equals(owner.getEmail())) {
            // Confirmed already, or the owner's own address (the account verification covers it).
            throw ChannelRuleException.alreadyConfirmed();
        }
        confirmations.request(owner, channel.getTarget());
    }

    @Transactional
    public void deleteChannel(Long userId, Long channelId) {
        NotificationChannel channel = requireOwnedChannel(userId, channelId);
        if (channelRepository.countByUserId(userId) <= 1) {
            throw ChannelRuleException.lastChannel();
        }
        channelRepository.delete(channel);
        log.info("Deleted channelId={} for userId={}", channelId, userId);
    }

    /**
     * Sends a sample alert right away, on the request thread, so the user sees the real outcome.
     * Deliberately not {@code @Transactional}: SMTP can take seconds, and holding a database
     * connection across it buys nothing. Nothing is written to {@code notifications}: a test
     * belongs to no incident.
     */
    public void sendTestAlert(Long userId, Long channelId) {
        NotificationChannel channel = requireOwnedChannel(userId, channelId);
        User owner = requireUser(userId);
        requireAllowedType(owner.getPlan(), channel.getType());
        if (!channel.confirmedFor(owner)) {
            throw channel.getTarget().equals(owner.getEmail())
                    ? ChannelRuleException.emailNotVerified()
                    : ChannelRuleException.notConfirmed();
        }
        NotificationSender sender = senders.get(channel.getType());
        if (sender == null) {
            throw ChannelRuleException.unsupported(label(channel.getType()));
        }
        if (!testThrottle.tryAcquire(userId)) {
            throw new TooManyAttemptsException("Too many test alerts. Try again in a few minutes.");
        }
        try {
            sender.send(channel.getTarget(), messages.test());
        } catch (Exception e) {
            // Never the target or the exception message: both can carry a webhook URL.
            log.warn("Test alert failed for channelId={} via {}: {}",
                    channelId, channel.getType(), e.getClass().getSimpleName());
            throw ChannelRuleException.deliveryFailed();
        }
        log.info("Sent test alert for channelId={} via {}", channelId, channel.getType());
    }

    /** Trims and canonicalises the target, or rejects it with a message for the target field. */
    static String normalize(ChannelType type, String raw) {
        String target = raw.trim();
        return switch (type) {
            case EMAIL -> {
                String email = UserMapper.normalizeEmail(target);
                if (email.length() > 255 || !EMAIL.matcher(email).matches()) {
                    throw new InvalidChannelTargetException("Enter a valid email address");
                }
                yield email;
            }
            case SLACK -> {
                if (!SLACK.matcher(target).matches()) {
                    throw new InvalidChannelTargetException(
                            "Paste a Slack incoming webhook URL (https://hooks.slack.com/services/…)");
                }
                yield target;
            }
            case SMS -> {
                String phone = PHONE_SEPARATORS.matcher(target).replaceAll("");
                if (!PHONE.matcher(phone).matches()) {
                    throw new InvalidChannelTargetException("Use international format, e.g. +85512345678");
                }
                yield phone;
            }
            // No plan offers these yet, so requireAllowedType stops them first.
            case TELEGRAM, WEBHOOK -> throw new InvalidChannelTargetException(
                    label(type) + " channels are not supported yet");
        };
    }

    private void requireAllowedType(Plan plan, ChannelType type) {
        if (!planLimits.allowsChannel(plan, type)) {
            throw PlanLimitExceededException.notIncluded(plan, type + " alerts");
        }
    }

    private static String label(ChannelType type) {
        return switch (type) {
            case EMAIL -> "Email";
            case SLACK -> "Slack";
            case SMS -> "SMS";
            case TELEGRAM -> "Telegram";
            case WEBHOOK -> "Webhook";
        };
    }

    /** Missing and other-tenant channels both surface as 404, so ids cannot be probed. */
    private NotificationChannel requireOwnedChannel(Long userId, Long channelId) {
        return channelRepository.findByIdAndUserId(channelId, userId)
                .orElseThrow(() -> new ChannelNotFoundException(channelId));
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated user " + userId + " no longer exists"));
    }
}
