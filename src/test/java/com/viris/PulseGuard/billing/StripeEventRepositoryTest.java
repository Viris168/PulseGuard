package com.viris.PulseGuard.billing;

import com.viris.PulseGuard.billing.repository.StripeEventRepository;
import com.viris.PulseGuard.common.AbstractRepositoryTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class StripeEventRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    StripeEventRepository events;

    @Test
    void recognisesAlreadyProcessedEventById() {
        events.saveAndFlush(new StripeEvent("evt_1"));
        em.clear();

        assertThat(events.existsById("evt_1")).isTrue();
        assertThat(events.existsById("evt_2")).isFalse();
    }

    @Test
    void setsProcessedAtWhenRecordingEvent() {
        events.saveAndFlush(new StripeEvent("evt_1"));
        em.clear();

        assertThat(events.findById("evt_1").orElseThrow().getProcessedAt()).isNotNull();
    }

    @Test
    void recordsAnEventOnlyOnce() {
        assertThat(events.insertIfAbsent("evt_1")).isEqualTo(1);
        assertThat(events.insertIfAbsent("evt_1")).isZero();
        assertThat(events.insertIfAbsent("evt_2")).isEqualTo(1);
    }
}
