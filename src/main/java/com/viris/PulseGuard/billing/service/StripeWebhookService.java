package com.viris.PulseGuard.billing.service;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import com.viris.PulseGuard.billing.repository.StripeEventRepository;
import com.viris.PulseGuard.billing.StripeProperties;
import com.viris.PulseGuard.billing.SubscriptionSyncService;
import com.viris.PulseGuard.billing.stripe.StripeGateway;
import com.viris.PulseGuard.billing.stripe.SubscriptionSnapshot;
import com.viris.PulseGuard.common.exception.InvalidWebhookException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.Set;

/**
 * Turns a Stripe webhook into a subscription sync.
 *
 * <ol>
 *   <li>Verify the signature; anything unsigned, forged or older than 5 minutes is rejected.</li>
 *   <li>Skip events already processed (Stripe delivers at least once).</li>
 *   <li>Fetch the subscription from Stripe, outside any transaction. The event is only a
 *       trigger: its body may be stale, since events can arrive out of order.</li>
 *   <li>In one transaction, record the event id and sync. If the sync fails, the record rolls
 *       back with it, the request fails, and Stripe delivers the event again later.</li>
 * </ol>
 */
@Service
public class StripeWebhookService {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookService.class);

    /** Every event that can change what a customer pays for. Anything else is acknowledged and ignored. */
    static final Set<String> SYNC_EVENTS = Set.of(
            "checkout.session.completed",
            "customer.subscription.created",
            "customer.subscription.updated",
            "customer.subscription.deleted",
            "invoice.payment_failed");

    private final StripeProperties properties;
    private final StripeGateway gateway;
    private final StripeEventRepository eventRepository;
    private final SubscriptionSyncService syncService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate writeTx;

    public StripeWebhookService(StripeProperties properties,
                                StripeGateway gateway,
                                StripeEventRepository eventRepository,
                                SubscriptionSyncService syncService,
                                ObjectMapper objectMapper,
                                PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.gateway = gateway;
        this.eventRepository = eventRepository;
        this.syncService = syncService;
        this.objectMapper = objectMapper;
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    /**
     * @param payload         the request body exactly as received; re-serialized JSON would not
     *                        match the signature.
     * @param signatureHeader the {@code Stripe-Signature} header, may be null.
     * @throws InvalidWebhookException if the request is not provably from Stripe.
     */
    public void handle(String payload, String signatureHeader) {
        Event event = verify(payload, signatureHeader);
        String eventId = event.getId();
        String type = event.getType();

        if (!SYNC_EVENTS.contains(type)) {
            log.debug("Ignoring Stripe event {} of type {}", eventId, type);
            return;
        }
        // Cheap early exit for redeliveries; the insert below is what actually guarantees once.
        if (eventRepository.existsById(eventId)) {
            log.info("Skipping Stripe event {} ({}): already processed", eventId, type);
            return;
        }
        Optional<String> subscriptionId = subscriptionIdOf(event);
        if (subscriptionId.isEmpty()) {
            log.info("Ignoring Stripe event {} ({}): not about a subscription", eventId, type);
            return;
        }

        // Outside the transaction: a slow Stripe call must not hold a connection and row locks.
        SubscriptionSnapshot snapshot = gateway.retrieveSubscription(subscriptionId.get());
        try {
            writeTx.executeWithoutResult(status -> {
                if (eventRepository.insertIfAbsent(eventId) == 0) {
                    log.info("Skipping Stripe event {} ({}): processed by a concurrent delivery", eventId, type);
                    return;
                }
                syncService.sync(snapshot);
                log.info("Processed Stripe event {} ({}) for subscription {}", eventId, type, snapshot.id());
            });
        } catch (RuntimeException e) {
            log.error("Stripe event {} ({}) failed and will be retried by Stripe: {}", eventId, type, e.getMessage());
            throw e;
        }
    }

    private Event verify(String payload, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new InvalidWebhookException("Missing Stripe-Signature header");
        }
        try {
            return Webhook.constructEvent(payload, signatureHeader, properties.webhookSecret());
        } catch (SignatureVerificationException e) {
            log.warn("Rejected Stripe webhook: {}", e.getMessage());
            throw new InvalidWebhookException("Invalid Stripe signature", e);
        } catch (RuntimeException e) {
            // Signed but unparseable; cannot come from Stripe.
            log.warn("Rejected Stripe webhook: malformed payload");
            throw new InvalidWebhookException("Malformed Stripe event", e);
        }
    }

    /**
     * Read from the raw JSON rather than the SDK's typed object: the typed one is empty when the
     * endpoint's API version differs from the SDK's, and these few ids never move between versions
     * (except the invoice's, handled for both shapes).
     */
    private Optional<String> subscriptionIdOf(Event event) {
        JsonNode object = objectMapper.readTree(event.getDataObjectDeserializer().getRawJson());
        return switch (event.getType()) {
            case "checkout.session.completed" -> "subscription".equals(object.path("mode").asString())
                    ? idOf(object.path("subscription"))
                    : Optional.empty();
            case "invoice.payment_failed" -> idOf(object.path("parent").path("subscription_details").path("subscription"))
                    .or(() -> idOf(object.path("subscription")));
            default -> idOf(object.path("id"));
        };
    }

    /** An id field may be the id itself or, when expanded, the whole object. */
    private static Optional<String> idOf(JsonNode node) {
        JsonNode id = node.isObject() ? node.path("id") : node;
        return id.isString() && !id.stringValue().isBlank() ? Optional.of(id.stringValue()) : Optional.empty();
    }
}
