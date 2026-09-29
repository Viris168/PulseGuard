package com.viris.PulseGuard.billing;

import com.viris.PulseGuard.auth.account.AccountEmailEvents;
import com.viris.PulseGuard.billing.stripe.StripeGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * After an email change, points the Stripe customer at the new address, so receipts follow.
 * Best effort and after commit: a Stripe hiccup must not undo a change the user confirmed.
 */
@Component
public class StripeCustomerEmailSync {

    private static final Logger log = LoggerFactory.getLogger(StripeCustomerEmailSync.class);

    private final StripeGateway stripe;

    public StripeCustomerEmailSync(StripeGateway stripe) {
        this.stripe = stripe;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmailChanged(AccountEmailEvents.EmailChanged event) {
        if (event.stripeCustomerId() == null) {
            return; // never started a checkout: no Stripe customer to update
        }
        try {
            stripe.updateCustomerEmail(event.stripeCustomerId(), event.newEmail());
        } catch (RuntimeException e) {
            log.warn("Could not update the Stripe customer email for user {}", event.userId());
        }
    }
}
