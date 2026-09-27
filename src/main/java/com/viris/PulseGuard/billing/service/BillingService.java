package com.viris.PulseGuard.billing.service;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.billing.StripeProperties;
import com.viris.PulseGuard.billing.repository.SubscriptionRepository;
import com.viris.PulseGuard.billing.dto.BillingSummaryResponse;
import com.viris.PulseGuard.billing.dto.RedirectResponse;
import com.viris.PulseGuard.billing.stripe.CheckoutSessionRequest;
import com.viris.PulseGuard.billing.stripe.StripeGateway;
import com.viris.PulseGuard.common.exception.BillingRuleException;
import com.viris.PulseGuard.enumeration.Plan;
import com.viris.PulseGuard.monitor.MonitorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the billing page asks for. Nothing here changes a plan: Checkout and the portal happen on
 * Stripe, and the webhook ({@link StripeWebhookService}) is the only thing that applies the result.
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final MonitorRepository monitorRepository;
    private final StripeGateway gateway;
    private final StripeProperties properties;

    public BillingService(UserRepository userRepository,
                          SubscriptionRepository subscriptionRepository,
                          MonitorRepository monitorRepository,
                          StripeGateway gateway,
                          StripeProperties properties) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.monitorRepository = monitorRepository;
        this.gateway = gateway;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public BillingSummaryResponse summary(Long userId) {
        User user = requireUser(userId);
        var usage = new BillingSummaryResponse.Usage(monitorRepository.countByUserId(userId));
        return subscriptionRepository.findByUserId(userId)
                .filter(subscription -> subscription.getStatus().grantsPlan())
                .map(subscription -> new BillingSummaryResponse(
                        user.getPlan(),
                        subscription.getStatus().stripeValue(),
                        subscription.getCurrentPeriodEnd(),
                        subscription.isCancelAtPeriodEnd(),
                        usage))
                .orElseGet(() -> new BillingSummaryResponse(user.getPlan(), null, null, false, usage));
    }

    /**
     * Deliberately not {@code @Transactional}: it makes up to two Stripe calls, and holding a
     * database connection across them buys nothing. Each write is its own short statement.
     */
    public RedirectResponse startCheckout(Long userId, Plan plan) {
        if (plan == Plan.FREE) {
            throw BillingRuleException.notPurchasable();
        }
        User user = requireUser(userId);
        boolean subscribed = subscriptionRepository.findByUserId(userId)
                .map(subscription -> subscription.getStatus().grantsPlan())
                .orElse(false);
        if (subscribed || user.getPlan() != Plan.FREE) {
            throw BillingRuleException.alreadySubscribed();
        }

        String customerId = ensureCustomer(user);
        String url = gateway.createCheckoutSession(new CheckoutSessionRequest(
                customerId,
                properties.priceFor(plan),
                userId,
                // {CHECKOUT_SESSION_ID} is filled in by Stripe. The page only waits on this
                // redirect; the webhook is what actually upgrades the account.
                properties.appUrl("/billing?checkout=success&plan=" + plan + "&session_id={CHECKOUT_SESSION_ID}"),
                properties.appUrl("/billing?checkout=cancelled")));
        log.info("Started {} checkout for userId={}", plan, userId);
        return new RedirectResponse(url);
    }

    /** Card, invoices, plan changes and cancellation all live in Stripe's portal. */
    public RedirectResponse openPortal(Long userId) {
        String customerId = requireUser(userId).getStripeCustomerId();
        if (customerId == null) {
            throw BillingRuleException.noBillingAccount();
        }
        return new RedirectResponse(gateway.createPortalSession(customerId, properties.appUrl("/billing")));
    }

    /**
     * The customer is created on the first upgrade, not at sign-up: most accounts never pay.
     * Two concurrent first checkouts send the same idempotency key, so Stripe returns one
     * customer to both, and linking it twice is harmless.
     */
    private String ensureCustomer(User user) {
        if (user.getStripeCustomerId() != null) {
            return user.getStripeCustomerId();
        }
        String customerId = gateway.createCustomer(user.getId(), user.getEmail(), user.getName());
        if (userRepository.linkStripeCustomer(user.getId(), customerId) == 1) {
            log.info("Linked Stripe customer to userId={}", user.getId());
            return customerId;
        }
        // Linked in between by a concurrent request; use whatever won.
        return requireUser(user.getId()).getStripeCustomerId();
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user " + userId + " no longer exists"));
    }
}
