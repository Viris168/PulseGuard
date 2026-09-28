package com.viris.PulseGuard.billing;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.billing.repository.DeletedStripeCustomerRepository;
import com.viris.PulseGuard.billing.repository.SubscriptionRepository;
import com.viris.PulseGuard.billing.stripe.SubscriptionSnapshot;
import com.viris.PulseGuard.common.exception.BillingSyncException;
import com.viris.PulseGuard.enumeration.Plan;
import com.viris.PulseGuard.enumeration.SubscriptionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Writes a subscription as Stripe has it now into {@code subscriptions}, and sets the account's
 * plan from it. Stripe is the source of truth: this never decides billing, it only mirrors it.
 *
 * <p>Idempotent, and indifferent to event order: it is always given the current state
 * (fetched from Stripe, not taken from the event), so syncing twice or late changes nothing.
 */
@Service
public class SubscriptionSyncService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionSyncService.class);

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanChangeService planChangeService;
    private final DeletedStripeCustomerRepository deletedCustomers;
    private final StripeProperties stripeProperties;

    public SubscriptionSyncService(UserRepository userRepository,
                                   SubscriptionRepository subscriptionRepository,
                                   PlanChangeService planChangeService,
                                   StripeProperties stripeProperties,
                                   DeletedStripeCustomerRepository deletedCustomers) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.planChangeService = planChangeService;
        this.stripeProperties = stripeProperties;
        this.deletedCustomers = deletedCustomers;
    }

    /**
     * Events for the customer of a deleted account are acknowledged and ignored: the account
     * cancelled its subscription on the way out, and there is nothing left to sync.
     *
     * @throws BillingSyncException     if no account has this customer, or a live subscription
     *                                  uses a price that is not configured. Both fail the webhook
     *                                  so Stripe retries once the data or config is fixed.
     * @throws IllegalArgumentException for a status this SDK version does not know.
     */
    @Transactional
    public void sync(SubscriptionSnapshot snapshot) {
        // Locked: concurrent events for this account wait here, then see what the first one wrote.
        User user = userRepository.lockByStripeCustomerId(snapshot.customerId()).orElse(null);
        if (user == null) {
            if (snapshot.customerId() != null && deletedCustomers.existsById(snapshot.customerId())) {
                log.info("Ignoring subscription {} event: its account was deleted", snapshot.id());
                return;
            }
            throw BillingSyncException.unknownCustomer(snapshot.id(), snapshot.customerId());
        }
        SubscriptionStatus status = SubscriptionStatus.fromStripe(snapshot.status());
        Optional<Plan> pricePlan = Optional.ofNullable(snapshot.priceId()).flatMap(stripeProperties::planForPrice);
        if (status.grantsPlan() && pricePlan.isEmpty()) {
            throw BillingSyncException.unknownPrice(snapshot.id(), snapshot.priceId());
        }

        Subscription row = subscriptionRepository.findByUserId(user.getId()).orElse(null);
        if (row != null && !snapshot.id().equals(row.getStripeSubscriptionId())) {
            if (row.getStatus().grantsPlan() && !status.grantsPlan()) {
                // A late event for an old subscription must not end the one the user pays for now.
                log.info("Ignoring {} subscription {} for userId={}: {} is the live one",
                        status.stripeValue(), snapshot.id(), user.getId(), row.getStripeSubscriptionId());
                return;
            }
            if (row.getStatus().grantsPlan()) {
                log.warn("userId={} has two live subscriptions ({} and {}); following the newer event",
                        user.getId(), row.getStripeSubscriptionId(), snapshot.id());
            }
        }
        if (row == null) {
            row = new Subscription();
            row.setUser(user);
        }
        row.setStripeSubscriptionId(snapshot.id());
        row.setStatus(status);
        // The plan that was bought; kept even once the subscription ends, for the record.
        row.setPlan(pricePlan.orElse(row.getPlan() != null ? row.getPlan() : Plan.FREE));
        row.setCurrentPeriodEnd(snapshot.currentPeriodEnd());
        row.setCancelAtPeriodEnd(snapshot.cancelAtPeriodEnd());
        subscriptionRepository.save(row);

        Plan effectivePlan = status.grantsPlan() ? pricePlan.orElseThrow() : Plan.FREE;
        planChangeService.applyPlan(user.getId(), effectivePlan);

        log.info("Synced subscription {} for userId={}: status={}, cancelAtPeriodEnd={}, plan={}",
                snapshot.id(), user.getId(), status.stripeValue(), snapshot.cancelAtPeriodEnd(), effectivePlan);
    }
}
