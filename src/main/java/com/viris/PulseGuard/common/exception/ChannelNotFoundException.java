package com.viris.PulseGuard.common.exception;

/**
 * Thrown when a channel does not exist, or exists but belongs to another user.
 * Both cases map to 404 so the API never reveals other tenants' channel ids.
 */
public class ChannelNotFoundException extends RuntimeException {
    public ChannelNotFoundException(Long channelId) {
        super("Channel not found: " + channelId);
    }
}
