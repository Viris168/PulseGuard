package com.viris.PulseGuard.common.exception;

import org.springframework.http.HttpStatus;

/**
 * A billing request that is well-formed but not allowed in the account's current state.
 * The message is safe to show the user.
 */
public class BillingRuleException extends RuntimeException {

    private final HttpStatus status;

    private BillingRuleException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public static BillingRuleException notPurchasable() {
        return new BillingRuleException(HttpStatus.BAD_REQUEST, "The Free plan can't be bought. Choose Pro or Business.");
    }

    /** Plan changes on a live subscription go through the portal, so nobody pays for two. */
    public static BillingRuleException alreadySubscribed() {
        return new BillingRuleException(HttpStatus.CONFLICT,
                "You already have a subscription. Change or cancel it from Manage billing.");
    }

    public static BillingRuleException noBillingAccount() {
        return new BillingRuleException(HttpStatus.CONFLICT,
                "There's no billing account yet. Upgrade to a paid plan first.");
    }

    public HttpStatus getStatus() {
        return status;
    }
}
