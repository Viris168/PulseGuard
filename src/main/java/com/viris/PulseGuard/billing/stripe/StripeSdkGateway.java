package com.viris.PulseGuard.billing.stripe;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import com.viris.PulseGuard.common.exception.PaymentProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** {@link StripeGateway} on the official SDK. Logs Stripe's request id on failure, never keys or payloads. */
@Component
public class StripeSdkGateway implements StripeGateway {

    private static final Logger log = LoggerFactory.getLogger(StripeSdkGateway.class);

    /** Metadata key tying Stripe objects back to {@code users.id}. */
    static final String USER_ID_METADATA = "userId";

    private final StripeClient client;

    public StripeSdkGateway(StripeClient client) {
        this.client = client;
    }

    @Override
    public String createCustomer(Long userId, String email, String name) {
        CustomerCreateParams params = CustomerCreateParams.builder()
                .setEmail(email)
                .setName(name)
                .putMetadata(USER_ID_METADATA, String.valueOf(userId))
                .build();
        RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey("pg-customer-" + userId)
                .build();
        try {
            return client.v1().customers().create(params, options).getId();
        } catch (StripeException e) {
            throw failure("create customer for userId=" + userId, e);
        }
    }

    @Override
    public String createCheckoutSession(CheckoutSessionRequest request) {
        String userId = String.valueOf(request.userId());
        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setCustomer(request.customerId())
                .setClientReferenceId(userId)
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setPrice(request.priceId())
                        .setQuantity(1L)
                        .build())
                .setSubscriptionData(SessionCreateParams.SubscriptionData.builder()
                        .putMetadata(USER_ID_METADATA, userId)
                        .build())
                .setSuccessUrl(request.successUrl())
                .setCancelUrl(request.cancelUrl())
                .build();
        try {
            return client.v1().checkout().sessions().create(params).getUrl();
        } catch (StripeException e) {
            throw failure("create checkout session for userId=" + userId, e);
        }
    }

    @Override
    public String createPortalSession(String customerId, String returnUrl) {
        com.stripe.param.billingportal.SessionCreateParams params =
                com.stripe.param.billingportal.SessionCreateParams.builder()
                        .setCustomer(customerId)
                        .setReturnUrl(returnUrl)
                        .build();
        try {
            return client.v1().billingPortal().sessions().create(params).getUrl();
        } catch (StripeException e) {
            throw failure("create portal session", e);
        }
    }

    @Override
    public SubscriptionSnapshot retrieveSubscription(String subscriptionId) {
        try {
            return toSnapshot(client.v1().subscriptions().retrieve(subscriptionId));
        } catch (StripeException e) {
            throw failure("retrieve subscription " + subscriptionId, e);
        }
    }

    private static SubscriptionSnapshot toSnapshot(Subscription subscription) {
        // One price per subscription is all PulseGuard sells; the portal swaps it, never adds one.
        List<SubscriptionItem> items = subscription.getItems() == null ? List.of() : subscription.getItems().getData();
        if (items.size() != 1) {
            log.warn("Stripe subscription {} has {} items, expected exactly 1", subscription.getId(), items.size());
        }
        SubscriptionItem item = items.isEmpty() ? null : items.getFirst();
        Instant periodEnd = item == null || item.getCurrentPeriodEnd() == null
                ? null : Instant.ofEpochSecond(item.getCurrentPeriodEnd());

        // Two ways to schedule an end. The Customer Portal (current API versions) sets cancel_at
        // to the period end and leaves cancel_at_period_end false; the API can also set the flag.
        // Either way the subscription is ending, on the earlier of the two dates.
        Instant cancelAt = subscription.getCancelAt() == null ? null : Instant.ofEpochSecond(subscription.getCancelAt());
        boolean ending = Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd()) || cancelAt != null;
        Instant endsOrRenews = cancelAt != null && (periodEnd == null || cancelAt.isBefore(periodEnd)) ? cancelAt : periodEnd;

        return new SubscriptionSnapshot(
                subscription.getId(),
                subscription.getCustomer(),
                subscription.getStatus(),
                item == null || item.getPrice() == null ? null : item.getPrice().getId(),
                endsOrRenews,
                ending);
    }

    private static PaymentProviderException failure(String action, StripeException e) {
        log.error("Stripe call failed: {} (status={}, code={}, requestId={})",
                action, e.getStatusCode(), e.getCode(), e.getRequestId());
        return new PaymentProviderException(e);
    }
}
