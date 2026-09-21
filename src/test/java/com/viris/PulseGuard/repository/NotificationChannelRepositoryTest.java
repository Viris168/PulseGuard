package com.viris.PulseGuard.repository;

import com.viris.PulseGuard.enumeration.ChannelType;
import com.viris.PulseGuard.model.NotificationChannel;
import com.viris.PulseGuard.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationChannelRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    NotificationChannelRepository channels;

    @Test
    void listsOnlyChannelsOwnedByUser() {
        User alice = newUser("alice@example.com");
        User bob = newUser("bob@example.com");
        newChannel(alice, ChannelType.EMAIL, true);
        newChannel(bob, ChannelType.SLACK, true);

        assertThat(channels.findAllByUserId(alice.getId())).hasSize(1);
        assertThat(channels.countByUserId(bob.getId())).isEqualTo(1);
    }

    @Test
    void cannotFetchAnotherUsersChannelById() {
        User alice = newUser("alice@example.com");
        User bob = newUser("bob@example.com");
        NotificationChannel channel = newChannel(alice, ChannelType.EMAIL, true);

        assertThat(channels.findByIdAndUserId(channel.getId(), alice.getId())).isPresent();
        assertThat(channels.findByIdAndUserId(channel.getId(), bob.getId())).isEmpty();
    }

    @Test
    void returnsOnlyEnabledChannels() {
        User user = newUser("a@example.com");
        NotificationChannel enabled = newChannel(user, ChannelType.EMAIL, true);
        newChannel(user, ChannelType.SLACK, false);

        assertThat(channels.findAllByUserIdAndEnabledTrue(user.getId()))
                .extracting(NotificationChannel::getId).containsExactly(enabled.getId());
    }
}
