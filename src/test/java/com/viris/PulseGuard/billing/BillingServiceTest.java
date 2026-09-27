package com.viris.PulseGuard.billing;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.billing.dto.BillingSummaryResponse;
import com.viris.PulseGuard.billing.repository.SubscriptionRepository;
import com.viris.PulseGuard.billing.service.BillingService;
import com.viris.PulseGuard.billing.stripe.CheckoutSessionRequest;
import com.viris.PulseGuard.billing.stripe.StripeGateway;
import com.viris.PulseGuard.common.exception.BillingRuleException;
import com.viris.PulseGuard.enumeration.Plan;
import com.viris.PulseGuard.enumeration.SubscriptionStatus;
import com.viris.PulseGuard.monitor.MonitorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BillingServiceTest {

    private static final Long USER_ID = 7L;
    private static final Instant PERIOD_END = Instant.parse("2026-10-28T00:00:00Z");

    @Mock
    private UserRepository userRepository;
    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private MonitorRepository monitorRepository;
    @Mock
    private StripeGateway gateway;

    private BillingService service;
    private User user;

    @BeforeEach
    void setUp() {
        StripeProperties properties = new StripeProperties("sk_test", "whsec_test",
                Map.of(Plan.PRO, "price_pro", Plan.BUSINESS, "price_business"),
                URI.create("https://app.example.com/"), Duration.ofSeconds(5), Duration.ofSeconds(20), 0);
        service = new BillingService(userRepository, subscriptionRepository, monitorRepository, gateway, properties);
        user = User.builder().id(USER_ID).name("Dara").email("dara@example.com").plan(Plan.FREE).build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(subscriptionRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(gateway.createCheckoutSession(any())).thenReturn("https://checkout.stripe.com/c/pay/cs_1");
    }

    private void subscription(SubscriptionStatus status, boolean cancelAtPeriodEnd) {
        Subscription row = new Subscription();
        row.setUser(user);
        row.setStripeSubscriptionId("sub_1");
        row.setPlan(Plan.PRO);
        row.setStatus(status);
        row.setCurrentPeriodEnd(PERIOD_END);
        row.setCancelAtPeriodEnd(cancelAtPeriodEnd);
        when(subscriptionRepository.findByUserId(USER_ID)).thenReturn(Optional.of(row));
    }

    // --- summary ----------------------------------------------------------

    @Test
    void summaryOfAFreeAccountHasNoSubscription() {
        when(monitorRepository.countByUserId(USER_ID)).thenReturn(2L);

        BillingSummaryResponse summary = service.summary(USER_ID);

        assertThat(summary).isEqualTo(new BillingSummaryResponse(
                Plan.FREE, null, null, false, new BillingSummaryResponse.Usage(2)));
    }

    @Test
    void summaryReportsALiveSubscriptionInStripesLowercaseStatus() {
        user.setPlan(Plan.PRO);
        subscription(SubscriptionStatus.PAST_DUE, true);

        BillingSummaryResponse summary = service.summary(USER_ID);

        assertThat(summary.plan()).isEqualTo(Plan.PRO);
        assertThat(summary.status()).isEqualTo("past_due");
        assertThat(summary.currentPeriodEnd()).isEqualTo(PERIOD_END);
        assertThat(summary.cancelAtPeriodEnd()).isTrue();
    }

    @Test
    void summaryHidesAnEndedSubscription() {
        subscription(SubscriptionStatus.CANCELED, false);

        BillingSummaryResponse summary = service.summary(USER_ID);

        assertThat(summary.status()).isNull();
        assertThat(summary.currentPeriodEnd()).isNull();
    }

    // --- checkout ---------------------------------------------------------

    @Test
    void firstCheckoutCreatesAndLinksTheCustomer() {
        when(gateway.createCustomer(USER_ID, "dara@example.com", "Dara")).thenReturn("cus_new");
        when(userRepository.linkStripeCustomer(USER_ID, "cus_new")).thenReturn(1);

        var response = service.startCheckout(USER_ID, Plan.PRO);

        assertThat(response.url()).isEqualTo("https://checkout.stripe.com/c/pay/cs_1");
        ArgumentCaptor<CheckoutSessionRequest> request = ArgumentCaptor.forClass(CheckoutSessionRequest.class);
        verify(gateway).createCheckoutSession(request.capture());
        assertThat(request.getValue()).isEqualTo(new CheckoutSessionRequest(
                "cus_new", "price_pro", USER_ID,
                "https://app.example.com/billing?checkout=success&plan=PRO&session_id={CHECKOUT_SESSION_ID}",
                "https://app.example.com/billing?checkout=cancelled"));
    }

    @Test
    void laterCheckoutReusesTheLinkedCustomer() {
        user.setStripeCustomerId("cus_existing");

        service.startCheckout(USER_ID, Plan.BUSINESS);

        verify(gateway, never()).createCustomer(anyLong(), anyString(), anyString());
        ArgumentCaptor<CheckoutSessionRequest> request = ArgumentCaptor.forClass(CheckoutSessionRequest.class);
        verify(gateway).createCheckoutSession(request.capture());
        assertThat(request.getValue().customerId()).isEqualTo("cus_existing");
        assertThat(request.getValue().priceId()).isEqualTo("price_business");
    }

    @Test
    void usesTheCustomerAConcurrentCheckoutLinkedFirst() {
        when(gateway.createCustomer(anyLong(), anyString(), anyString())).thenReturn("cus_mine");
        when(userRepository.linkStripeCustomer(USER_ID, "cus_mine")).thenReturn(0);
        User reloaded = User.builder().id(USER_ID).stripeCustomerId("cus_theirs").build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user), Optional.of(reloaded));

        service.startCheckout(USER_ID, Plan.PRO);

        ArgumentCaptor<CheckoutSessionRequest> request = ArgumentCaptor.forClass(CheckoutSessionRequest.class);
        verify(gateway).createCheckoutSession(request.capture());
        assertThat(request.getValue().customerId()).isEqualTo("cus_theirs");
    }

    @Test
    void freePlanCannotBeBought() {
        assertThatThrownBy(() -> service.startCheckout(USER_ID, Plan.FREE))
                .isInstanceOf(BillingRuleException.class)
                .extracting("status").hasToString("400 BAD_REQUEST");
        verifyNoInteractions(gateway);
    }

    @Test
    void liveSubscriptionMustChangePlanInThePortal() {
        user.setPlan(Plan.PRO);
        subscription(SubscriptionStatus.ACTIVE, false);

        assertThatThrownBy(() -> service.startCheckout(USER_ID, Plan.BUSINESS))
                .isInstanceOf(BillingRuleException.class)
                .extracting("status").hasToString("409 CONFLICT");
        verifyNoInteractions(gateway);
    }

    @Test
    void pastDueSubscriptionCannotStartASecondOne() {
        user.setPlan(Plan.PRO);
        subscription(SubscriptionStatus.PAST_DUE, false);

        assertThatThrownBy(() -> service.startCheckout(USER_ID, Plan.PRO))
                .isInstanceOf(BillingRuleException.class);
    }

    @Test
    void endedSubscriptionCanBuyAgain() {
        user.setStripeCustomerId("cus_existing");
        subscription(SubscriptionStatus.CANCELED, false);

        service.startCheckout(USER_ID, Plan.PRO);

        verify(gateway).createCheckoutSession(any());
    }

    // --- portal -----------------------------------------------------------

    @Test
    void portalReturnsToTheBillingPage() {
        user.setStripeCustomerId("cus_existing");
        when(gateway.createPortalSession("cus_existing", "https://app.example.com/billing"))
                .thenReturn("https://billing.stripe.com/p/session/bps_1");

        assertThat(service.openPortal(USER_ID).url()).isEqualTo("https://billing.stripe.com/p/session/bps_1");
    }

    @Test
    void portalNeedsABillingAccount() {
        assertThatThrownBy(() -> service.openPortal(USER_ID))
                .isInstanceOf(BillingRuleException.class)
                .extracting("status").hasToString("409 CONFLICT");
        verifyNoInteractions(gateway);
    }
}
