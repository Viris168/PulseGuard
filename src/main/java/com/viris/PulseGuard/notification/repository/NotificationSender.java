package com.viris.PulseGuard.notification.repository;

import com.viris.PulseGuard.enumeration.ChannelType;
import com.viris.PulseGuard.notification.dto.AlertMessage;

/**
 * One implementation per channel type; Spring collects them all, so a new channel is a new
 * class and nothing else changes. {@code send} throws on failure, never swallows it: the
 * caller records every failed alert.
 */
public interface NotificationSender {

    ChannelType type();

    /** @param target the channel's destination: an email address, a webhook URL, ... */
    void send(String target, AlertMessage message);
}
