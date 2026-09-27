package com.viris.PulseGuard.billing;

import com.viris.PulseGuard.billing.repository.SubscriptionRepository;
import com.viris.PulseGuard.common.AbstractRepositoryTest;
import com.viris.PulseGuard.enumeration.Plan;
import com.viris.PulseGuard.enumeration.SubscriptionStatus;
import com.viris.PulseGuard.auth.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubscriptionRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    SubscriptionRepository subscriptions;

    private Subscription subscription(User user, String stripeId) {
        Subscription s = new Subscription();
        s.setUser(user);
        s.setStripeSubscriptionId(stripeId);
        s.setPlan(Plan.PRO);
        s.setStatus(SubscriptionStatus.ACTIVE);
        return s;
    }

    @Test
    void findsSubscriptionByUserAndByStripeId() {
        User user = newUser("a@example.com");
        subscriptions.saveAndFlush(subscription(user, "sub_1"));

        assertThat(subscriptions.findByUserId(user.getId())).isPresent();
        assertThat(subscriptions.findByStripeSubscriptionId("sub_1")).isPresent();
        assertThat(subscriptions.findByStripeSubscriptionId("sub_missing")).isEmpty();
    }

    @Test
    void storesCancellationFlagAndStampsUpdatedAt() {
        Subscription saved = subscriptions.saveAndFlush(subscription(newUser("a@example.com"), "sub_1"));
        Instant created = saved.getUpdatedAt();
        assertThat(created).isNotNull();
        assertThat(saved.isCancelAtPeriodEnd()).isFalse();

        saved.setCancelAtPeriodEnd(true);
        subscriptions.saveAndFlush(saved);
        em.clear();

        Subscription reloaded = subscriptions.findByStripeSubscriptionId("sub_1").orElseThrow();
        assertThat(reloaded.isCancelAtPeriodEnd()).isTrue();
        assertThat(reloaded.getUpdatedAt()).isAfterOrEqualTo(created);
    }

    @Test
    void rejectsSecondSubscriptionForSameUser() {
        User user = newUser("a@example.com");
        subscriptions.saveAndFlush(subscription(user, "sub_1"));

        assertThatThrownBy(() -> subscriptions.saveAndFlush(subscription(user, "sub_2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateStripeSubscriptionId() {
        subscriptions.saveAndFlush(subscription(newUser("a@example.com"), "sub_1"));

        assertThatThrownBy(() -> subscriptions.saveAndFlush(subscription(newUser("b@example.com"), "sub_1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
