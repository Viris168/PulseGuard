package com.viris.PulseGuard.billing;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.enumeration.ChannelType;
import com.viris.PulseGuard.enumeration.Plan;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import com.viris.PulseGuard.notification.NotificationChannel;
import com.viris.PulseGuard.notification.repository.NotificationChannelRepository;
import com.viris.PulseGuard.scheduling.SchedulerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanChangeServiceTest {

    private static final Long USER_ID = 7L;

    @Mock
    private UserRepository userRepository;
    @Mock
    private MonitorRepository monitorRepository;
    @Mock
    private NotificationChannelRepository channelRepository;
    @Mock
    private SchedulerService schedulerService;

    private PlanChangeService service;
    private User user;

    @BeforeEach
    void setUp() {
        // PlanLimits is a pure lookup — exercise the real limits, not a stub.
        service = new PlanChangeService(userRepository, monitorRepository, channelRepository,
                schedulerService, new PlanLimits());
        user = User.builder().id(USER_ID).email("dara@example.com").plan(Plan.PRO).build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    }

    private Monitor monitor(long id, int intervalSeconds, boolean active) {
        Monitor monitor = new Monitor();
        monitor.setId(id);
        monitor.setUser(user);
        monitor.setIntervalSeconds(intervalSeconds);
        monitor.setActive(active);
        return monitor;
    }

    private NotificationChannel channel(ChannelType type) {
        NotificationChannel channel = new NotificationChannel();
        channel.setUser(user);
        channel.setType(type);
        channel.setTarget("target");
        channel.setEnabled(true);
        return channel;
    }

    @Test
    void downgradeSlowsFasterMonitorsToThePlanMinimum() {
        Monitor running = monitor(1, 60, true);
        Monitor paused = monitor(2, 120, false);
        when(monitorRepository.findAllByUserIdAndIntervalSecondsLessThan(USER_ID, 300))
                .thenReturn(List.of(running, paused));

        boolean changed = service.applyPlan(USER_ID, Plan.FREE);

        assertThat(changed).isTrue();
        assertThat(user.getPlan()).isEqualTo(Plan.FREE);
        assertThat(running.getIntervalSeconds()).isEqualTo(300);
        assertThat(paused.getIntervalSeconds()).isEqualTo(300);
    }

    @Test
    void downgradeReschedulesOnlyRunningMonitors() {
        Monitor running = monitor(1, 60, true);
        Monitor paused = monitor(2, 60, false);
        when(monitorRepository.findAllByUserIdAndIntervalSecondsLessThan(USER_ID, 300))
                .thenReturn(List.of(running, paused));

        service.applyPlan(USER_ID, Plan.FREE);

        verify(schedulerService).reschedule(running);
        verify(schedulerService, never()).reschedule(paused);
    }

    @Test
    void downgradeSwitchesOffChannelsThePlanNoLongerIncludes() {
        NotificationChannel email = channel(ChannelType.EMAIL);
        NotificationChannel slack = channel(ChannelType.SLACK);
        when(channelRepository.findAllByUserIdAndEnabledTrue(USER_ID)).thenReturn(List.of(email, slack));

        service.applyPlan(USER_ID, Plan.FREE);

        assertThat(email.isEnabled()).isTrue();
        assertThat(slack.isEnabled()).isFalse();
    }

    @Test
    void businessToProKeepsOneMinuteChecksAndSlack() {
        user.setPlan(Plan.BUSINESS);
        NotificationChannel slack = channel(ChannelType.SLACK);
        NotificationChannel sms = channel(ChannelType.SMS);
        when(monitorRepository.findAllByUserIdAndIntervalSecondsLessThan(USER_ID, 60)).thenReturn(List.of());
        when(channelRepository.findAllByUserIdAndEnabledTrue(USER_ID)).thenReturn(List.of(slack, sms));

        service.applyPlan(USER_ID, Plan.PRO);

        assertThat(slack.isEnabled()).isTrue();
        assertThat(sms.isEnabled()).isFalse();
        verifyNoInteractions(schedulerService);
    }

    @Test
    void upgradeOnlyChangesThePlan() {
        user.setPlan(Plan.FREE);
        when(monitorRepository.findAllByUserIdAndIntervalSecondsLessThan(USER_ID, 60)).thenReturn(List.of());
        when(channelRepository.findAllByUserIdAndEnabledTrue(USER_ID)).thenReturn(List.of(channel(ChannelType.EMAIL)));

        boolean changed = service.applyPlan(USER_ID, Plan.PRO);

        assertThat(changed).isTrue();
        assertThat(user.getPlan()).isEqualTo(Plan.PRO);
        verifyNoInteractions(schedulerService);
    }

    @Test
    void samePlanChangesNothing() {
        boolean changed = service.applyPlan(USER_ID, Plan.PRO);

        assertThat(changed).isFalse();
        verify(monitorRepository, never()).findAllByUserIdAndIntervalSecondsLessThan(anyLong(), anyInt());
        verify(channelRepository, never()).findAllByUserIdAndEnabledTrue(any());
        verifyNoInteractions(schedulerService);
    }
}
