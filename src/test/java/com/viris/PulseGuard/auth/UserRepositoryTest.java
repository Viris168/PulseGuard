package com.viris.PulseGuard.auth;

import com.viris.PulseGuard.common.AbstractRepositoryTest;
import com.viris.PulseGuard.enumeration.Plan;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    UserRepository users;

    @Test
    void findsUserByEmail() {
        newUser("a@example.com");

        assertThat(users.findByEmail("a@example.com")).isPresent();
        assertThat(users.findByEmail("missing@example.com")).isEmpty();
    }

    @Test
    void reportsWhetherEmailExists() {
        newUser("a@example.com");

        assertThat(users.existsByEmail("a@example.com")).isTrue();
        assertThat(users.existsByEmail("b@example.com")).isFalse();
    }

    @Test
    void newUserDefaultsToFreePlan() {
        User saved = newUser("a@example.com");
        em.clear();

        assertThat(users.findById(saved.getId()).orElseThrow().getPlan()).isEqualTo(Plan.FREE);
    }

    @Test
    void findsUserByStripeCustomerId() {
        User user = newUser("a@example.com");
        user.setStripeCustomerId("cus_123");
        em.persistAndFlush(user);

        assertThat(users.findByStripeCustomerId("cus_123")).isPresent();
    }

    @Test
    void rejectsSecondUserWithSameStripeCustomerId() {
        User first = newUser("a@example.com");
        first.setStripeCustomerId("cus_123");
        em.persistAndFlush(first);
        User second = newUser("b@example.com");
        second.setStripeCustomerId("cus_123");

        assertThatThrownBy(() -> users.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void linksAStripeCustomerOnlyOnceAndLeavesThePlanAlone() {
        User user = newUser("a@example.com");
        user.setPlan(Plan.PRO);
        em.persistAndFlush(user);

        assertThat(users.linkStripeCustomer(user.getId(), "cus_first")).isEqualTo(1);
        assertThat(users.linkStripeCustomer(user.getId(), "cus_second")).isZero();
        em.clear();

        User reloaded = users.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getStripeCustomerId()).isEqualTo("cus_first");
        assertThat(reloaded.getPlan()).isEqualTo(Plan.PRO);
    }

    @Test
    void allowsManyUsersWithoutStripeCustomer() {
        newUser("a@example.com");
        newUser("b@example.com");

        assertThat(users.count()).isEqualTo(2);
    }

    @Test
    void rejectsDuplicateEmail() {
        newUser("a@example.com");
        User duplicate = new User();
        duplicate.setEmail("a@example.com");
        duplicate.setName("Other User");
        duplicate.setPasswordHash("hash");

        assertThatThrownBy(() -> users.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
