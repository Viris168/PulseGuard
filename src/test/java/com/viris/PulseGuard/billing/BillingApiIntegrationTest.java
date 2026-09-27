package com.viris.PulseGuard.billing;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.auth.security.InMemoryLoginRateLimiter;
import com.viris.PulseGuard.auth.security.InMemoryTokenDenylist;
import com.viris.PulseGuard.auth.security.LoginRateLimiter;
import com.viris.PulseGuard.auth.security.TokenDenylist;
import com.viris.PulseGuard.billing.repository.SubscriptionRepository;
import com.viris.PulseGuard.billing.stripe.CheckoutSessionRequest;
import com.viris.PulseGuard.billing.stripe.StripeGateway;
import com.viris.PulseGuard.common.exception.PaymentProviderException;
import com.viris.PulseGuard.enumeration.Plan;
import com.viris.PulseGuard.enumeration.SubscriptionStatus;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The billing page's endpoints over HTTP, with a real database and a fake Stripe API. */
@SpringBootTest
@AutoConfigureMockMvc
class BillingApiIntegrationTest {

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
    ObjectMapper objectMapper;
    @Autowired
    UserRepository users;
    @Autowired
    SubscriptionRepository subscriptions;
    @Autowired
    MonitorRepository monitors;

    private String token;
    private User user;

    @BeforeEach
    void setUp() throws Exception {
        users.deleteAll();
        token = register("dara@example.com");
        user = users.findByEmail("dara@example.com").orElseThrow();
    }

    private String register(String email) throws Exception {
        String body = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Dara","email":"%s","password":"Sup3rSecret!"}
                                """.formatted(email)))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asString();
    }

    private String bearer() {
        return "Bearer " + token;
    }

    private void subscribe(Plan plan, SubscriptionStatus status, boolean cancelAtPeriodEnd) {
        user.setPlan(plan);
        user.setStripeCustomerId("cus_123");
        user = users.save(user);
        Subscription row = new Subscription();
        row.setUser(user);
        row.setStripeSubscriptionId("sub_1");
        row.setPlan(plan);
        row.setStatus(status);
        row.setCurrentPeriodEnd(Instant.parse("2026-10-28T00:00:00Z"));
        row.setCancelAtPeriodEnd(cancelAtPeriodEnd);
        subscriptions.save(row);
    }

    // --- GET /api/billing/subscription ------------------------------------

    @Test
    void freeAccountSummaryCountsMonitors() throws Exception {
        Monitor monitor = new Monitor();
        monitor.setUser(user);
        monitor.setName("API");
        monitor.setUrl("https://example.com/health");
        monitors.save(monitor);

        mockMvc.perform(get("/api/billing/subscription").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("FREE"))
                .andExpect(jsonPath("$.status").isEmpty())
                .andExpect(jsonPath("$.currentPeriodEnd").isEmpty())
                .andExpect(jsonPath("$.cancelAtPeriodEnd").value(false))
                .andExpect(jsonPath("$.usage.monitors").value(1));
    }

    @Test
    void paidAccountSummaryMatchesTheFrontendContract() throws Exception {
        subscribe(Plan.PRO, SubscriptionStatus.ACTIVE, true);

        mockMvc.perform(get("/api/billing/subscription").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("PRO"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.currentPeriodEnd").value("2026-10-28T00:00:00Z"))
                .andExpect(jsonPath("$.cancelAtPeriodEnd").value(true));
    }

    // --- POST /api/billing/checkout ---------------------------------------

    @Test
    void checkoutLinksACustomerOnceAndReturnsTheStripeUrl() throws Exception {
        when(gateway.createCustomer(anyLong(), anyString(), anyString())).thenReturn("cus_new");
        when(gateway.createCheckoutSession(any())).thenReturn("https://checkout.stripe.com/c/pay/cs_1");

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/billing/checkout").header(HttpHeaders.AUTHORIZATION, bearer())
                            .contentType(MediaType.APPLICATION_JSON).content("""
                                    {"plan": "PRO"}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.url").value("https://checkout.stripe.com/c/pay/cs_1"));
        }

        verify(gateway, times(1)).createCustomer(user.getId(), "dara@example.com", "Dara");
        User reloaded = users.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getStripeCustomerId()).isEqualTo("cus_new");
        assertThat(reloaded.getPlan()).as("only the webhook upgrades").isEqualTo(Plan.FREE);
        ArgumentCaptor<CheckoutSessionRequest> request = ArgumentCaptor.forClass(CheckoutSessionRequest.class);
        verify(gateway, times(2)).createCheckoutSession(request.capture());
        assertThat(request.getValue().priceId()).isEqualTo("price_test_pro");
        assertThat(request.getValue().successUrl()).startsWith("http://localhost:5173/billing?checkout=success");
    }

    @Test
    void checkoutForTheFreePlanIsRejected() throws Exception {
        mockMvc.perform(post("/api/billing/checkout").header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"plan": "FREE"}"""))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(gateway);
    }

    @Test
    void checkoutWithoutAPlanIsRejected() throws Exception {
        mockMvc.perform(post("/api/billing/checkout").header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.plan").exists());
    }

    @Test
    void checkoutForAnUnknownPlanListsTheValidOnes() throws Exception {
        mockMvc.perform(post("/api/billing/checkout").header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"plan": "GOLD"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.plan").value("must be one of FREE, PRO, BUSINESS"));

        verifyNoInteractions(gateway);
    }

    @Test
    void malformedJsonIsABadRequestNotAnAuthFailure() throws Exception {
        mockMvc.perform(post("/api/billing/checkout").header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request body is not valid JSON."));
    }

    @Test
    void checkoutWhileSubscribedIsAConflict() throws Exception {
        subscribe(Plan.PRO, SubscriptionStatus.ACTIVE, false);

        mockMvc.perform(post("/api/billing/checkout").header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"plan": "BUSINESS"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "You already have a subscription. Change or cancel it from Manage billing."));

        verifyNoInteractions(gateway);
    }

    @Test
    void stripeOutageIsABadGateway() throws Exception {
        when(gateway.createCustomer(anyLong(), anyString(), anyString()))
                .thenThrow(new PaymentProviderException(new RuntimeException("connect timed out")));

        mockMvc.perform(post("/api/billing/checkout").header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"plan": "PRO"}"""))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("Billing is temporarily unavailable. Please try again in a moment."));

        assertThat(users.findById(user.getId()).orElseThrow().getStripeCustomerId()).isNull();
    }

    // --- POST /api/billing/portal -----------------------------------------

    @Test
    void portalOpensForASubscriber() throws Exception {
        subscribe(Plan.PRO, SubscriptionStatus.ACTIVE, false);
        when(gateway.createPortalSession("cus_123", "http://localhost:5173/billing"))
                .thenReturn("https://billing.stripe.com/p/session/bps_1");

        mockMvc.perform(post("/api/billing/portal").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://billing.stripe.com/p/session/bps_1"));
    }

    @Test
    void portalWithoutABillingAccountIsAConflict() throws Exception {
        mockMvc.perform(post("/api/billing/portal").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isConflict());

        verifyNoInteractions(gateway);
    }

    // --- auth -------------------------------------------------------------

    @Test
    void everyBillingEndpointNeedsAToken() throws Exception {
        mockMvc.perform(get("/api/billing/subscription")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/billing/checkout").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"plan": "PRO"}""")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/billing/portal")).andExpect(status().isUnauthorized());

        verifyNoInteractions(gateway);
    }
}
