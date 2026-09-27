package com.viris.PulseGuard.common.exception;

import org.springframework.http.HttpStatus;

/**
 * A channel request that is well-formed but not allowed right now: a duplicate, deleting the
 * last channel, or testing a type that has no sender yet. The message is safe to show the user.
 */
public class ChannelRuleException extends RuntimeException {

    private final HttpStatus status;

    private ChannelRuleException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public static ChannelRuleException duplicate() {
        return new ChannelRuleException(HttpStatus.CONFLICT, "That channel already exists.");
    }

    public static ChannelRuleException lastChannel() {
        return new ChannelRuleException(HttpStatus.BAD_REQUEST,
                "Keep at least one channel so you hear about outages.");
    }

    public static ChannelRuleException unsupported(String typeLabel) {
        return new ChannelRuleException(HttpStatus.BAD_REQUEST,
                typeLabel + " alerts can't be delivered yet.");
    }

    /** The provider refused or was unreachable. Its own message may quote the target, so it is dropped. */
    public static ChannelRuleException deliveryFailed() {
        return new ChannelRuleException(HttpStatus.BAD_GATEWAY,
                "The test alert could not be delivered. Check the target and try again.");
    }

    public HttpStatus getStatus() {
        return status;
    }
}
