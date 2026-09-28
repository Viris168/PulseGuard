package com.viris.PulseGuard.billing;

import com.viris.PulseGuard.billing.repository.DeletedStripeCustomerRepository;
import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.billing.repository.SubscriptionRepository;
import com.viris.PulseGuard.billing.stripe.SubscriptionSnapshot;
import com.viris.PulseGuard.common.exception.BillingSyncException;
import com.viris.PulseGuard.enumeration.Plan;
import com.viris.PulseGuard.enumeration.SubscriptionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriptionSyncServiceTest {

    private static final Long USER_ID = 7L;
    private static final String CUSTOMER = "cus_123";
    private static final Instant PERIOD_END = Instant.parse("2026-10-28T00:00:00Z");

    @Mock
    private UserRepository userRepository;
    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private PlanChangeService planChangeService;
    @Mock
    private DeletedStripeCustomerRepository deletedCustomers;

    private SubscriptionSyncService service;

    @BeforeEach
    void setUp() {
        StripeProperties properties = new StripeProperties("sk_test", "whsec_test",
                Map.of(Plan.PRO, "price_pro", Plan.BUSINESS, "price_business"),
                URI.create("http://localhost:5173"), Duration.ofSeconds(5), Duration.ofSeconds(20), 0);
        service = new SubscriptionSyncService(userRepository, subscriptionRepository, planChangeService, properties,
                deletedCustomers);
        User user = User.builder().id(USER_ID).email("dara@example.com").plan(Plan.FREE).build();
        when(userRepository.lockByStripeCustomerId(CUSTOMER)).thenReturn(Optional.of(user));
        when(subscriptionRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
    }

    private static SubscriptionSnapshot snapshot(String id, String status, String priceId, boolean cancelAtPeriodEnd) {
        return new SubscriptionSnapshot(id, CUSTOMER, status, priceId, PERIOD_END, cancelAtPeriodEnd);
    }

    private static SubscriptionSnapshot snapshot(String status, String priceId) {
        return snapshot("sub_1", status, priceId, false);
    }

    private Subscription existingRow(String stripeId, SubscriptionStatus status, Plan plan) {
        Subscription row = new Subscription();
        row.setUser(User.builder().id(USER_ID).build());
        row.setStripeSubscriptionId(stripeId);
        row.setStatus(status);
        row.setPlan(plan);
        when(subscriptionRepository.findByUserId(USER_ID)).thenReturn(Optional.of(row));
        return row;
    }

    private Subscription savedRow() {
        ArgumentCaptor<Subscription> saved = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(saved.capture());
        return saved.getValue();
    }

    @Test
    void activeSubscriptionGivesTheAccountThePlanOfItsPrice() {
        service.sync(snapshot("active", "price_business"));

        verify(planChangeService).applyPlan(USER_ID, Plan.BUSINESS);
        Subscription row = savedRow();
        assertThat(row.getStripeSubscriptionId()).isEqualTo("sub_1");
        assertThat(row.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(row.getPlan()).isEqualTo(Plan.BUSINESS);
        assertThat(row.getCurrentPeriodEnd()).isEqualTo(PERIOD_END);
        assertThat(row.getUser().getId()).isEqualTo(USER_ID);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "trialing, PRO",
            "active, PRO",
            "past_due, PRO",
            "incomplete, FREE",
            "incomplete_expired, FREE",
            "canceled, FREE",
            "unpaid, FREE",
            "paused, FREE",
    })
    void mapsEveryStripeStatusToAPlan(String status, Plan expected) {
        service.sync(snapshot(status, "price_pro"));

        verify(planChangeService).applyPlan(USER_ID, expected);
    }

    @Test
    void cancellingAtPeriodEndKeepsThePlanUntilStripeEndsIt() {
        service.sync(snapshot("sub_1", "active", "price_pro", true));

        verify(planChangeService).applyPlan(USER_ID, Plan.PRO);
        assertThat(savedRow().isCancelAtPeriodEnd()).isTrue();
    }

    @Test
    void endedSubscriptionMovesToFreeButRemembersWhatWasBought() {
        existingRow("sub_1", SubscriptionStatus.ACTIVE, Plan.PRO);

        service.sync(snapshot("canceled", "price_pro"));

        verify(planChangeService).applyPlan(USER_ID, Plan.FREE);
        Subscription row = savedRow();
        assertThat(row.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(row.getPlan()).isEqualTo(Plan.PRO);
    }

    @Test
    void updatesTheExistingRowInsteadOfAddingOne() {
        Subscription row = existingRow("sub_1", SubscriptionStatus.ACTIVE, Plan.PRO);

        service.sync(snapshot("active", "price_business"));

        assertThat(savedRow()).isSameAs(row);
        assertThat(row.getPlan()).isEqualTo(Plan.BUSINESS);
    }

    @Test
    void newSubscriptionReplacesAnEndedOne() {
        Subscription row = existingRow("sub_old", SubscriptionStatus.CANCELED, Plan.PRO);

        service.sync(snapshot("sub_new", "active", "price_pro", false));

        assertThat(row.getStripeSubscriptionId()).isEqualTo("sub_new");
        verify(planChangeService).applyPlan(USER_ID, Plan.PRO);
    }

    @Test
    void lateEventForAnOldSubscriptionDoesNotEndTheLiveOne() {
        existingRow("sub_new", SubscriptionStatus.ACTIVE, Plan.PRO);

        service.sync(snapshot("sub_old", "canceled", "price_pro", false));

        verify(subscriptionRepository, never()).save(any());
        verifyNoInteractions(planChangeService);
    }

    @Test
    void failsForACustomerNoAccountIsLinkedTo() {
        when(userRepository.lockByStripeCustomerId(CUSTOMER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sync(snapshot("active", "price_pro")))
                .isInstanceOf(BillingSyncException.class)
                .hasMessageContaining(CUSTOMER);
        verifyNoInteractions(planChangeService);
    }

    @Test
    void failsForALiveSubscriptionOnAnUnconfiguredPrice() {
        assertThatThrownBy(() -> service.sync(snapshot("active", "price_unknown")))
                .isInstanceOf(BillingSyncException.class)
                .hasMessageContaining("price_unknown");
        verify(subscriptionRepository, never()).save(any());
        verifyNoInteractions(planChangeService);
    }

    @Test
    void stillDowngradesAnEndedSubscriptionOnAnUnconfiguredPrice() {
        service.sync(snapshot("canceled", "price_retired"));

        verify(planChangeService).applyPlan(USER_ID, Plan.FREE);
        assertThat(savedRow().getPlan()).isEqualTo(Plan.FREE);
    }

    @Test
    void failsForAStatusThisVersionDoesNotKnow() {
        assertThatThrownBy(() -> service.sync(snapshot("hibernating", "price_pro")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(planChangeService);
    }

    @Test
    void eventsForADeletedAccountsCustomerAreAcknowledged() {
        when(userRepository.lockByStripeCustomerId("cus_gone")).thenReturn(Optional.empty());
        when(deletedCustomers.existsById("cus_gone")).thenReturn(true);
        SubscriptionSnapshot cancelled = new SubscriptionSnapshot("sub_9", "cus_gone", "canceled", "price_pro", PERIOD_END, false);

        assertThatCode(() -> service.sync(cancelled)).doesNotThrowAnyException();
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void anyOtherUnknownCustomerStillFailsSoStripeRetries() {
        when(userRepository.lockByStripeCustomerId("cus_new")).thenReturn(Optional.empty());
        when(deletedCustomers.existsById("cus_new")).thenReturn(false);
        SubscriptionSnapshot early = new SubscriptionSnapshot("sub_8", "cus_new", "active", "price_pro", PERIOD_END, false);

        assertThatThrownBy(() -> service.sync(early)).isInstanceOf(BillingSyncException.class);
    }
}
