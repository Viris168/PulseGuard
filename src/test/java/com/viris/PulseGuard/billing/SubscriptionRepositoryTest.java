package com.viris.PulseGuard.billing;

import com.viris.PulseGuard.common.AbstractRepositoryTest;
import com.viris.PulseGuard.enumeration.Plan;
import com.viris.PulseGuard.auth.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

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
        s.setStatus("active");
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
