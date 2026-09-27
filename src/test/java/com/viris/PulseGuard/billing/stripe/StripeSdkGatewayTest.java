package com.viris.PulseGuard.billing.stripe;

import com.stripe.StripeClient;
import com.viris.PulseGuard.common.exception.PaymentProviderException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The real SDK against a fake Stripe API: checks what is sent and how responses are read. */
class StripeSdkGatewayTest {

    private MockWebServer stripe;
    private StripeSdkGateway gateway;

    @BeforeEach
    void setUp() throws IOException {
        stripe = new MockWebServer();
        stripe.start();
        String apiBase = stripe.url("/").toString().replaceAll("/$", "");
        StripeClient client = StripeClient.builder()
                .setApiKey("sk_test_fake")
                .setApiBase(apiBase)
                .setMaxNetworkRetries(0)
                .build();
        gateway = new StripeSdkGateway(client);
    }

    @AfterEach
    void tearDown() throws IOException {
        stripe.shutdown();
    }

    private void respond(int status, String json) {
        stripe.enqueue(new MockResponse().setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setHeader("Request-Id", "req_test")
                .setBody(json));
    }

    private static String form(RecordedRequest request) {
        return URLDecoder.decode(request.getBody().readUtf8(), StandardCharsets.UTF_8);
    }

    @Test
    void createsCustomerTaggedWithUserIdAndAPerUserIdempotencyKey() throws InterruptedException {
        respond(200, """
                {"id": "cus_123", "object": "customer"}""");

        String customerId = gateway.createCustomer(42L, "dara@example.com", "Dara");

        assertThat(customerId).isEqualTo("cus_123");
        RecordedRequest request = stripe.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/v1/customers");
        assertThat(request.getHeader("Idempotency-Key")).isEqualTo("pg-customer-42");
        assertThat(form(request)).contains("email=dara@example.com", "name=Dara", "metadata[userId]=42");
    }

    @Test
    void createsSubscriptionCheckoutForOnePriceAndReturnsItsUrl() throws InterruptedException {
        respond(200, """
                {"id": "cs_test_1", "object": "checkout.session", "url": "https://checkout.stripe.com/c/pay/cs_test_1"}""");

        String url = gateway.createCheckoutSession(new CheckoutSessionRequest(
                "cus_123", "price_pro", 42L,
                "http://localhost:5173/billing?checkout=success&session_id={CHECKOUT_SESSION_ID}",
                "http://localhost:5173/billing?checkout=cancelled"));

        assertThat(url).isEqualTo("https://checkout.stripe.com/c/pay/cs_test_1");
        RecordedRequest request = stripe.takeRequest();
        assertThat(request.getPath()).isEqualTo("/v1/checkout/sessions");
        assertThat(form(request)).contains(
                "mode=subscription",
                "customer=cus_123",
                "client_reference_id=42",
                "line_items[0][price]=price_pro",
                "line_items[0][quantity]=1",
                "subscription_data[metadata][userId]=42",
                "success_url=http://localhost:5173/billing?checkout=success&session_id={CHECKOUT_SESSION_ID}",
                "cancel_url=http://localhost:5173/billing?checkout=cancelled");
    }

    @Test
    void createsPortalSessionForTheCustomer() throws InterruptedException {
        respond(200, """
                {"id": "bps_1", "object": "billing_portal.session", "url": "https://billing.stripe.com/p/session/bps_1"}""");

        String url = gateway.createPortalSession("cus_123", "http://localhost:5173/billing");

        assertThat(url).isEqualTo("https://billing.stripe.com/p/session/bps_1");
        RecordedRequest request = stripe.takeRequest();
        assertThat(request.getPath()).isEqualTo("/v1/billing_portal/sessions");
        assertThat(form(request)).contains("customer=cus_123", "return_url=http://localhost:5173/billing");
    }

    @Test
    void readsPriceAndPeriodEndFromTheSubscriptionItem() throws InterruptedException {
        respond(200, """
                {
                  "id": "sub_1", "object": "subscription", "customer": "cus_123",
                  "status": "past_due", "cancel_at_period_end": true,
                  "items": {"object": "list", "has_more": false, "url": "/v1/subscription_items", "data": [
                    {"id": "si_1", "object": "subscription_item", "current_period_end": 1790000000,
                     "price": {"id": "price_pro", "object": "price"}}
                  ]}
                }""");

        SubscriptionSnapshot snapshot = gateway.retrieveSubscription("sub_1");

        assertThat(stripe.takeRequest().getPath()).isEqualTo("/v1/subscriptions/sub_1");
        assertThat(snapshot).isEqualTo(new SubscriptionSnapshot(
                "sub_1", "cus_123", "past_due", "price_pro", Instant.ofEpochSecond(1790000000L), true));
    }

    @Test
    void portalCancellationViaCancelAtCountsAsEndingAtPeriodEnd() {
        // What the Customer Portal really sends on current API versions (seen in the sandbox):
        // the flag stays false, cancel_at is the period end.
        respond(200, """
                {
                  "id": "sub_1", "object": "subscription", "customer": "cus_123", "status": "active",
                  "cancel_at_period_end": false, "cancel_at": 1793128227,
                  "items": {"object": "list", "has_more": false, "url": "/v1/subscription_items", "data": [
                    {"id": "si_1", "object": "subscription_item", "current_period_end": 1793128227,
                     "price": {"id": "price_pro", "object": "price"}}
                  ]}
                }""");

        SubscriptionSnapshot snapshot = gateway.retrieveSubscription("sub_1");

        assertThat(snapshot.cancelAtPeriodEnd()).isTrue();
        assertThat(snapshot.currentPeriodEnd()).isEqualTo(Instant.ofEpochSecond(1793128227L));
    }

    @Test
    void cancellationBeforeThePeriodEndsReportsTheEarlierDate() {
        respond(200, """
                {
                  "id": "sub_1", "object": "subscription", "customer": "cus_123", "status": "active",
                  "cancel_at_period_end": false, "cancel_at": 1791000000,
                  "items": {"object": "list", "has_more": false, "url": "/v1/subscription_items", "data": [
                    {"id": "si_1", "object": "subscription_item", "current_period_end": 1793128227,
                     "price": {"id": "price_pro", "object": "price"}}
                  ]}
                }""");

        SubscriptionSnapshot snapshot = gateway.retrieveSubscription("sub_1");

        assertThat(snapshot.cancelAtPeriodEnd()).isTrue();
        assertThat(snapshot.currentPeriodEnd()).isEqualTo(Instant.ofEpochSecond(1791000000L));
    }

    @Test
    void renewingSubscriptionIsNotEnding() {
        respond(200, """
                {
                  "id": "sub_1", "object": "subscription", "customer": "cus_123", "status": "active",
                  "cancel_at_period_end": false, "cancel_at": null,
                  "items": {"object": "list", "has_more": false, "url": "/v1/subscription_items", "data": [
                    {"id": "si_1", "object": "subscription_item", "current_period_end": 1793128227,
                     "price": {"id": "price_pro", "object": "price"}}
                  ]}
                }""");

        SubscriptionSnapshot snapshot = gateway.retrieveSubscription("sub_1");

        assertThat(snapshot.cancelAtPeriodEnd()).isFalse();
        assertThat(snapshot.currentPeriodEnd()).isEqualTo(Instant.ofEpochSecond(1793128227L));
    }

    @Test
    void toleratesASubscriptionWithoutItems() {
        respond(200, """
                {"id": "sub_1", "object": "subscription", "customer": "cus_123", "status": "canceled",
                 "items": {"object": "list", "has_more": false, "url": "/v1/subscription_items", "data": []}}""");

        SubscriptionSnapshot snapshot = gateway.retrieveSubscription("sub_1");

        assertThat(snapshot.priceId()).isNull();
        assertThat(snapshot.currentPeriodEnd()).isNull();
        assertThat(snapshot.cancelAtPeriodEnd()).isFalse();
    }

    @Test
    void wrapsStripeErrorsWithoutLeakingTheirText() {
        respond(404, """
                {"error": {"type": "invalid_request_error", "code": "resource_missing",
                           "message": "No such subscription: 'sub_missing'"}}""");

        assertThatThrownBy(() -> gateway.retrieveSubscription("sub_missing"))
                .isInstanceOf(PaymentProviderException.class)
                .hasMessageNotContaining("sub_missing");
    }

    @Test
    void wrapsServerErrors() {
        respond(500, """
                {"error": {"type": "api_error", "message": "Something went wrong"}}""");

        assertThatThrownBy(() -> gateway.createPortalSession("cus_123", "http://localhost:5173/billing"))
                .isInstanceOf(PaymentProviderException.class);
    }
}
