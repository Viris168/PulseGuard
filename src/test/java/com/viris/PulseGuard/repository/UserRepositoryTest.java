package com.viris.PulseGuard.repository;

import com.viris.PulseGuard.enumeration.Plan;
import com.viris.PulseGuard.model.User;
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
    void rejectsDuplicateEmail() {
        newUser("a@example.com");
        User duplicate = new User();
        duplicate.setEmail("a@example.com");
        duplicate.setPasswordHash("hash");

        assertThatThrownBy(() -> users.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
