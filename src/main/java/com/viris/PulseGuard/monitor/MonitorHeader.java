package com.viris.PulseGuard.monitor;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Locale;
import java.util.Set;

/** One request header an HTTP check sends. */
@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class MonitorHeader {

    /** Always secret, whatever their value looks like. */
    private static final Set<String> SECRET_NAMES = Set.of("authorization", "proxy-authorization", "cookie");
    /** Name fragments that usually mean a credential: X-Api-Key, X-Auth-Token, Client-Secret... */
    private static final String[] SECRET_HINTS = {"auth", "token", "key", "secret", "password", "session", "signature"};

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 2000)
    private String value;

    @Column(nullable = false)
    private int position;

    /**
     * Whether this header's value is kept write-only. Erring towards secret costs the owner
     * nothing but retyping a value; erring the other way hands their credentials to anyone
     * who can read the monitor (including through a PulseGuard API key).
     */
    public static boolean isSecretName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (SECRET_NAMES.contains(lower)) {
            return true;
        }
        for (String hint : SECRET_HINTS) {
            if (lower.contains(hint)) {
                return true;
            }
        }
        return false;
    }
}
