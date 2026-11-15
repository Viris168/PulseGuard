package com.viris.PulseGuard.auth.account;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.auth.jwt.JwtService;
import com.viris.PulseGuard.auth.security.CurrentPasswordCheck;
import com.viris.PulseGuard.auth.security.TokenDenylist;
import com.viris.PulseGuard.billing.DeletedStripeCustomer;
import com.viris.PulseGuard.billing.Subscription;
import com.viris.PulseGuard.billing.repository.DeletedStripeCustomerRepository;
import com.viris.PulseGuard.billing.repository.SubscriptionRepository;
import com.viris.PulseGuard.billing.stripe.StripeGateway;
import com.viris.PulseGuard.common.exception.AccountDeletionException;
import com.viris.PulseGuard.common.exception.RevocationUnavailableException;
import com.viris.PulseGuard.enumeration.SubscriptionStatus;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import com.viris.PulseGuard.scheduling.SchedulerService;
import com.viris.PulseGuard.statuspage.StatusPageChangedEvent;
import com.viris.PulseGuard.statuspage.StatusPageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

/**
 * Deletes an account and everything it owns. The database does the heavy lifting: every table
 * that belongs to a user reaches it through ON DELETE CASCADE. What the database cannot do is
 * done here, in an order that never leaves a paying customer without an account: the Stripe
 * subscription is cancelled first, and if that fails nothing is deleted.
 */
@Service
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    private final UserRepository users;
    private final CurrentPasswordCheck passwordCheck;
    private final SubscriptionRepository subscriptions;
    private final StripeGateway stripe;
    private final MonitorRepository monitors;
    private final SchedulerService schedulerService;
    private final StatusPageRepository statusPages;
    private final TokenDenylist denylist;
    private final JwtService jwtService;
    private final ApplicationEventPublisher events;
    private final DeletedStripeCustomerRepository deletedCustomers;

    public AccountDeletionService(UserRepository users, CurrentPasswordCheck passwordCheck,
                                  SubscriptionRepository subscriptions, StripeGateway stripe,
                                  MonitorRepository monitors, SchedulerService schedulerService,
                                  StatusPageRepository statusPages, TokenDenylist denylist,
                                  JwtService jwtService, ApplicationEventPublisher events,
                                  DeletedStripeCustomerRepository deletedCustomers) {
        this.users = users;
        this.passwordCheck = passwordCheck;
        this.subscriptions = subscriptions;
        this.stripe = stripe;
        this.monitors = monitors;
        this.schedulerService = schedulerService;
        this.statusPages = statusPages;
        this.denylist = denylist;
        this.jwtService = jwtService;
        this.events = events;
        this.deletedCustomers = deletedCustomers;
    }

    @Transactional
    public void deleteAccount(Long userId, String currentPassword) {
        User user = users.findById(userId).orElseThrow();
        passwordCheck.verify(user, currentPassword);

        // 1. Stop billing. Immediately, not at period end: there is no account left to serve.
        subscriptions.findByUserId(userId)
                .filter(sub -> sub.getStripeSubscriptionId() != null && sub.getStatus() != SubscriptionStatus.CANCELED
                        && sub.getStatus() != SubscriptionStatus.INCOMPLETE_EXPIRED)
                .map(Subscription::getStripeSubscriptionId)
                .ifPresent(subscriptionId -> {
                    try {
                        stripe.cancelSubscription(subscriptionId);
                    } catch (RuntimeException e) {
                        log.error("Account deletion stopped for user {}: subscription cancel failed", userId);
                        throw new AccountDeletionException();
                    }
                });

        // 2. What lives outside these tables: check jobs in Quartz (same transaction), the
        //    cached public status page (evicted after commit), and live sessions (Redis).
        for (Monitor monitor : monitors.findAllByUserId(userId)) {
            schedulerService.unschedule(monitor.getId());
        }
        statusPages.findByUserId(userId).ifPresent(page ->
                events.publishEvent(new StatusPageChangedEvent(Set.of(page.getSlug()))));
        try {
            denylist.revokeAllForUser(userId, Instant.now(), jwtService.sessionRetention());
        } catch (RevocationUnavailableException e) {
            // Not worth undoing a deletion whose subscription is already cancelled: tokens of a
            // deleted user are refused anyway, since every request and refresh loads the user.
            log.warn("Deleted user {} without a revocation cut-off: store unavailable", userId);
        }

        // Late Stripe events for this customer (the cancellation above sends one) are then
        // acknowledged instead of failing the webhook for days; see SubscriptionSyncService.
        if (user.getStripeCustomerId() != null) {
            deletedCustomers.save(new DeletedStripeCustomer(user.getStripeCustomerId()));
        }

        // 3. Everything else goes with the row: monitors, checks, pings, incidents, alerts,
        //    channels, API keys, status page, subscription row, tokens.
        users.deleteByIdCascading(userId);
        log.info("Deleted account for user {}", userId);
    }
}
