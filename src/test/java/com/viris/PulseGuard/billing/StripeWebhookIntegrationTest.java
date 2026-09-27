package com.viris.PulseGuard.billing;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.auth.security.InMemoryLoginRateLimiter;
import com.viris.PulseGuard.auth.security.InMemoryTokenDenylist;
import com.viris.PulseGuard.auth.security.LoginRateLimiter;
import com.viris.PulseGuard.auth.security.TokenDenylist;
import com.viris.PulseGuard.billing.repository.StripeEventRepository;
import com.viris.PulseGuard.billing.repository.SubscriptionRepository;
import com.viris.PulseGuard.billing.stripe.StripeGateway;
import com.viris.PulseGuard.billing.stripe.SubscriptionSnapshot;
import com.viris.PulseGuard.common.exception.PaymentProviderException;
import com.viris.PulseGuard.enumeration.ChannelType;
import com.viris.PulseGuard.enumeration.Plan;
import com.viris.PulseGuard.enumeration.SubscriptionStatus;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import com.viris.PulseGuard.notification.NotificationChannel;
import com.viris.PulseGuard.notification.repository.NotificationChannelRepository;
import com.viris.PulseGuard.scheduling.SchedulerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The webhook end to end: real signature checks, real PostgreSQL, real Quartz job store.
 * Only the Stripe API is faked. Payloads are signed here with plain HMAC-SHA256, the way
 * Stripe documents it, rather than with the SDK's own helper, so a test cannot pass merely
 * because signing and verifying share a bug.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StripeWebhookIntegrationTest {

    /** Must match pulseguard.stripe.webhook-secret in src/test/resources/application.properties. */
    private static final String SECRET = "whsec_test_not_a_real_secret";
    private static final String CUSTOMER = "cus_test_123";
    private static final Instant PERIOD_END = Instant.parse("2026-10-28T00:00:00Z");

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @TestConfiguration
    static class TestBackends {
        @Bean
        @Primary
        TokenDenylist denylist() {
            return new InMemoryTokenDenylist();
        }

        @Bean
        @Primary
        LoginRateLimiter rateLimiter() {
            return new InMemoryLoginRateLimiter(5, 20);
        }
    }

    @MockitoBean
    StripeGateway gateway;

    @Autowired
    MockMvc mockMvc;
    @Autowired
    UserRepository users;
    @Autowired
    SubscriptionRepository subscriptions;
    @Autowired
    StripeEventRepository events;
    @Autowired
    MonitorRepository monitors;
    @Autowired
    NotificationChannelRepository channels;
    @Autowired
    SchedulerService schedulerService;

    private User user;

    @BeforeEach
    void setUp() {
        events.deleteAll();
        users.deleteAll();
        user = users.save(User.builder()
                .name("Dara")
                .email("dara@example.com")
                .passwordHash("hash")
                .stripeCustomerId(CUSTOMER)
                .build());
    }

    // --- helpers ----------------------------------------------------------

    private static String event(String id, String type, String object) {
        return """
                {"id": "%s", "object": "event", "type": "%s", "api_version": "2026-08-26.dahlia",
                 "created": 1790000000, "livemode": false, "data": {"object": %s}}""".formatted(id, type, object);
    }

    private static String subscriptionEvent(String id, String type) {
        return event(id, type, """
                {"id": "sub_1", "object": "subscription", "customer": "%s", "status": "active"}""".formatted(CUSTOMER));
    }

    private static SubscriptionSnapshot snapshot(String status, String priceId) {
        return new SubscriptionSnapshot("sub_1", CUSTOMER, status, priceId, PERIOD_END, false);
    }

    /** Stripe's scheme: v1 = hex(HMAC-SHA256(secret, "{t}.{payload}")). */
    private static String sign(String payload, long timestamp, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(digest);
    }

    private ResultActions deliver(String payload, String signatureHeader) throws Exception {
        var request = post("/api/stripe/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload.getBytes(StandardCharsets.UTF_8));
        if (signatureHeader != null) {
            request.header("Stripe-Signature", signatureHeader);
        }
        return mockMvc.perform(request);
    }

    private ResultActions deliver(String payload) throws Exception {
        return deliver(payload, sign(payload, Instant.now().getEpochSecond(), SECRET));
    }

    private Plan planOfUser() {
        return users.findById(user.getId()).orElseThrow().getPlan();
    }

    // --- happy paths ------------------------------------------------------

    @Test
    void subscriptionEventUpgradesTheAccountWithoutAToken() throws Exception {
        when(gateway.retrieveSubscription("sub_1")).thenReturn(snapshot("active", "price_test_pro"));

        deliver(subscriptionEvent("evt_1", "customer.subscription.created")).andExpect(status().isOk());

        assertThat(planOfUser()).isEqualTo(Plan.PRO);
        Subscription row = subscriptions.findByUserId(user.getId()).orElseThrow();
        assertThat(row.getStripeSubscriptionId()).isEqualTo("sub_1");
        assertThat(row.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(row.getCurrentPeriodEnd()).isEqualTo(PERIOD_END);
        assertThat(events.existsById("evt_1")).isTrue();
    }

    @Test
    void checkoutCompletedSyncsTheSubscriptionItCreated() throws Exception {
        when(gateway.retrieveSubscription("sub_1")).thenReturn(snapshot("active", "price_test_business"));

        deliver(event("evt_1", "checkout.session.completed", """
                {"id": "cs_1", "object": "checkout.session", "mode": "subscription",
                 "customer": "%s", "client_reference_id": "%d", "subscription": "sub_1"}"""
                .formatted(CUSTOMER, user.getId())))
                .andExpect(status().isOk());

        assertThat(planOfUser()).isEqualTo(Plan.BUSINESS);
    }

    @Test
    void failedInvoicePaymentKeepsThePlanWhileStripeRetries() throws Exception {
        when(gateway.retrieveSubscription("sub_1")).thenReturn(snapshot("past_due", "price_test_pro"));

        deliver(event("evt_1", "invoice.payment_failed", """
                {"id": "in_1", "object": "invoice", "customer": "%s",
                 "parent": {"type": "subscription_details", "subscription_details": {"subscription": "sub_1"}}}"""
                .formatted(CUSTOMER)))
                .andExpect(status().isOk());

        assertThat(planOfUser()).isEqualTo(Plan.PRO);
        assertThat(subscriptions.findByUserId(user.getId()).orElseThrow().getStatus())
                .isEqualTo(SubscriptionStatus.PAST_DUE);
    }

    @Test
    void deletedSubscriptionDowngradesAndSlowsMonitorsAndSwitchesOffSlack() throws Exception {
        user.setPlan(Plan.PRO);
        users.save(user);
        Monitor monitor = new Monitor();
        monitor.setUser(user);
        monitor.setName("API");
        monitor.setUrl("https://example.com/health");
        monitor.setIntervalSeconds(60);
        monitor = monitors.save(monitor);
        NotificationChannel slack = new NotificationChannel();
        slack.setUser(user);
        slack.setType(ChannelType.SLACK);
        slack.setTarget("https://hooks.slack.com/services/T000/B000/XXXX");
        slack = channels.save(slack);
        when(gateway.retrieveSubscription("sub_1")).thenReturn(snapshot("canceled", "price_test_pro"));

        deliver(subscriptionEvent("evt_1", "customer.subscription.deleted")).andExpect(status().isOk());

        assertThat(planOfUser()).isEqualTo(Plan.FREE);
        assertThat(monitors.findById(monitor.getId()).orElseThrow().getIntervalSeconds()).isEqualTo(300);
        assertThat(schedulerService.isScheduled(monitor.getId())).isTrue();
        assertThat(channels.findById(slack.getId()).orElseThrow().isEnabled()).isFalse();
    }

    @Test
    void keepsNonAsciiPayloadsVerifiable() throws Exception {
        when(gateway.retrieveSubscription("sub_1")).thenReturn(snapshot("active", "price_test_pro"));

        deliver(event("evt_1", "customer.subscription.updated", """
                {"id": "sub_1", "object": "subscription", "customer": "%s", "status": "active",
                 "description": "Đara’s café — ពិនិត្យ"}""".formatted(CUSTOMER)))
                .andExpect(status().isOk());

        assertThat(planOfUser()).isEqualTo(Plan.PRO);
    }

    // --- concurrency --------------------------------------------------------

    @Test
    void simultaneousEventsForANewSubscriptionBothSucceed() throws Exception {
        // What Stripe really does on a first payment: checkout.session.completed and
        // customer.subscription.created, same subscription, same instant. Several rounds,
        // because a race only shows up when the two actually overlap.
        when(gateway.retrieveSubscription("sub_1")).thenReturn(snapshot("active", "price_test_pro"));
        for (int round = 0; round < 8; round++) {
            events.deleteAll();
            subscriptions.deleteAll();
            String checkout = event("evt_c" + round, "checkout.session.completed", """
                    {"id": "cs_1", "object": "checkout.session", "mode": "subscription", "subscription": "sub_1"}""");
            String created = subscriptionEvent("evt_s" + round, "customer.subscription.created");

            var start = new java.util.concurrent.CountDownLatch(1);
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var first = pool.submit(() -> { start.await(); return deliver(checkout).andReturn().getResponse().getStatus(); });
                var second = pool.submit(() -> { start.await(); return deliver(created).andReturn().getResponse().getStatus(); });
                start.countDown();
                assertThat(first.get()).as("round %d checkout.session.completed", round).isEqualTo(200);
                assertThat(second.get()).as("round %d customer.subscription.created", round).isEqualTo(200);
            }
            assertThat(subscriptions.count()).isEqualTo(1);
            assertThat(events.count()).isEqualTo(2);
        }
        assertThat(planOfUser()).isEqualTo(Plan.PRO);
    }

    // --- deduplication ----------------------------------------------------

    @Test
    void redeliveredEventIsProcessedOnce() throws Exception {
        when(gateway.retrieveSubscription("sub_1")).thenReturn(snapshot("active", "price_test_pro"));
        String payload = subscriptionEvent("evt_1", "customer.subscription.updated");

        deliver(payload).andExpect(status().isOk());
        deliver(payload).andExpect(status().isOk());

        verify(gateway, times(1)).retrieveSubscription("sub_1");
        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void unhandledEventTypesAreAcknowledgedAndIgnored() throws Exception {
        deliver(event("evt_1", "customer.created", """
                {"id": "%s", "object": "customer"}""".formatted(CUSTOMER)))
                .andExpect(status().isOk());

        verifyNoInteractions(gateway);
        assertThat(events.count()).isZero();
    }

    @Test
    void oneTimeCheckoutIsIgnored() throws Exception {
        deliver(event("evt_1", "checkout.session.completed", """
                {"id": "cs_1", "object": "checkout.session", "mode": "payment", "subscription": null}"""))
                .andExpect(status().isOk());

        verifyNoInteractions(gateway);
    }

    // --- rejected requests ------------------------------------------------

    @Test
    void forgedSignatureIsRejectedAndChangesNothing() throws Exception {
        String payload = subscriptionEvent("evt_1", "customer.subscription.updated");

        deliver(payload, sign(payload, Instant.now().getEpochSecond(), "whsec_attacker_guess"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid webhook signature."));

        verifyNoInteractions(gateway);
        assertThat(planOfUser()).isEqualTo(Plan.FREE);
        assertThat(events.count()).isZero();
    }

    @Test
    void tamperedPayloadIsRejected() throws Exception {
        String payload = subscriptionEvent("evt_1", "customer.subscription.updated");
        String header = sign(payload, Instant.now().getEpochSecond(), SECRET);

        deliver(payload.replace("evt_1", "evt_2"), header).andExpect(status().isBadRequest());

        verifyNoInteractions(gateway);
    }

    @Test
    void missingSignatureIsRejected() throws Exception {
        deliver(subscriptionEvent("evt_1", "customer.subscription.updated"), null)
                .andExpect(status().isBadRequest());

        verifyNoInteractions(gateway);
    }

    @Test
    void replayedOldEventIsRejected() throws Exception {
        String payload = subscriptionEvent("evt_1", "customer.subscription.updated");
        long tenMinutesAgo = Instant.now().minusSeconds(600).getEpochSecond();

        deliver(payload, sign(payload, tenMinutesAgo, SECRET)).andExpect(status().isBadRequest());

        verifyNoInteractions(gateway);
    }

    // --- failures Stripe retries ------------------------------------------

    @Test
    void unconfiguredPriceFailsLeavesTheEventUnrecordedAndSucceedsOnRetry() throws Exception {
        String payload = subscriptionEvent("evt_1", "customer.subscription.updated");
        when(gateway.retrieveSubscription("sub_1")).thenReturn(snapshot("active", "price_not_in_config"));

        deliver(payload).andExpect(status().isInternalServerError());

        assertThat(planOfUser()).isEqualTo(Plan.FREE);
        assertThat(events.existsById("evt_1")).isFalse();
        assertThat(subscriptions.findByUserId(user.getId())).isEmpty();

        // Config fixed; Stripe redelivers the same event.
        when(gateway.retrieveSubscription("sub_1")).thenReturn(snapshot("active", "price_test_pro"));
        deliver(payload).andExpect(status().isOk());

        assertThat(planOfUser()).isEqualTo(Plan.PRO);
        assertThat(events.existsById("evt_1")).isTrue();
    }

    @Test
    void stripeOutageFailsWithoutRecordingTheEvent() throws Exception {
        when(gateway.retrieveSubscription(any())).thenThrow(new PaymentProviderException(new RuntimeException("timeout")));

        deliver(subscriptionEvent("evt_1", "customer.subscription.updated")).andExpect(status().isBadGateway());

        assertThat(events.existsById("evt_1")).isFalse();
    }

    @Test
    void customerNoAccountIsLinkedToFailsSoStripeRetries() throws Exception {
        when(gateway.retrieveSubscription("sub_1")).thenReturn(
                new SubscriptionSnapshot("sub_1", "cus_unknown", "active", "price_test_pro", PERIOD_END, false));

        deliver(subscriptionEvent("evt_1", "customer.subscription.created"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Event could not be processed."));

        assertThat(events.existsById("evt_1")).isFalse();
    }
}
