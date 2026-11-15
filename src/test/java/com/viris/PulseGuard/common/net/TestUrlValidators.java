package com.viris.PulseGuard.common.net;

import java.net.InetAddress;

/** SSRF guards for tests whose check target is a MockWebServer on this machine. */
public final class TestUrlValidators {

    private TestUrlValidators() {
    }

    /**
     * Resolves for real and lets loopback through, nothing else. The check client connects to
     * what the validator resolved, so a stubbed public answer would send the check off this
     * machine instead of to the local server.
     */
    public static SafeUrlValidator allowingLoopback() {
        return new SafeUrlValidator() {
            @Override
            protected boolean isBlocked(InetAddress address) {
                return !address.isLoopbackAddress() && super.isBlocked(address);
            }
        };
    }
}
