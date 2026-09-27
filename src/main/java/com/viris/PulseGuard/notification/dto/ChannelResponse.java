package com.viris.PulseGuard.notification.dto;

import com.viris.PulseGuard.enumeration.ChannelType;
import com.viris.PulseGuard.notification.NotificationChannel;
import com.viris.PulseGuard.notification.TargetMasker;

/** {@code target} is masked: a saved webhook URL is a credential and never leaves the server again. */
public record ChannelResponse(
        Long id,
        ChannelType type,
        String target,
        boolean enabled
) {
    public static ChannelResponse from(NotificationChannel channel) {
        return new ChannelResponse(
                channel.getId(),
                channel.getType(),
                TargetMasker.mask(channel.getType(), channel.getTarget()),
                channel.isEnabled()
        );
    }
}
