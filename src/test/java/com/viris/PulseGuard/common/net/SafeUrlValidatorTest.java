package com.viris.PulseGuard.common.net;

import com.viris.PulseGuard.common.exception.InvalidMonitorUrlException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeUrlValidatorTest {

    /** Resolves every host to the given literals, so no test touches real DNS. */
    private static SafeUrlValidator resolvingTo(String... literals) {
        return new SafeUrlValidator(host -> {
            InetAddress[] addresses = new InetAddress[literals.length];
            for (int i = 0; i < literals.length; i++) {
                addresses[i] = InetAddress.getByName(literals[i]);
            }
            return addresses;
        });
    }

    @Test
    void acceptsAPublicAddress() {
        assertThatCode(() -> resolvingTo("93.184.216.34").validate("https://example.com/health"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1",        // loopback
            "10.1.2.3",         // 10/8
            "172.16.5.4",       // 172.16/12
            "192.168.1.1",      // 192.168/16
            "169.254.169.254",  // cloud metadata
            "0.0.0.0",          // any-local
            "0.1.2.3",          // 0/8 this-network
            "100.64.0.1",       // carrier-grade NAT
            "240.0.0.1",        // reserved
            "::1",              // IPv6 loopback
            "fc00::1",          // IPv6 unique local
            "fe80::1"           // IPv6 link local
    })
    void rejectsPrivateAndReservedAddresses(String literal) {
        assertThatThrownBy(() -> resolvingTo(literal).validate("https://internal.example.com"))
                .isInstanceOf(InvalidMonitorUrlException.class)
                .hasMessageContaining("private or reserved");
    }

    @Test
    void rejectsWhenAnyResolvedAddressIsPrivate() {
        // A host that answers with one public and one internal address must not pass.
        assertThatThrownBy(() -> resolvingTo("93.184.216.34", "10.0.0.5")
                .validate("https://split-horizon.example.com"))
                .isInstanceOf(InvalidMonitorUrlException.class);
    }

    @Test
    void rejectsMessageDoesNotLeakTheResolvedAddress() {
        assertThatThrownBy(() -> resolvingTo("10.1.2.3").validate("https://internal.example.com"))
                .hasMessageNotContaining("10.1.2.3");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://example.com", "file:///etc/passwd", "gopher://example.com"})
    void rejectsNonHttpSchemes(String url) {
        assertThatThrownBy(() -> resolvingTo("93.184.216.34").validate(url))
                .isInstanceOf(InvalidMonitorUrlException.class);
    }

    @Test
    void rejectsMissingHost() {
        assertThatThrownBy(() -> resolvingTo("93.184.216.34").validate("https:///health"))
                .isInstanceOf(InvalidMonitorUrlException.class)
                .hasMessageContaining("missing host");
    }

    @Test
    void rejectsMalformedUrl() {
        assertThatThrownBy(() -> resolvingTo("93.184.216.34").validate("h t t p://nope"))
                .isInstanceOf(InvalidMonitorUrlException.class)
                .hasMessageContaining("not a valid URL");
    }

    @Test
    void rejectsUnresolvableHost() {
        SafeUrlValidator validator = new SafeUrlValidator(host -> {
            throw new UnknownHostException(host);
        });
        assertThatThrownBy(() -> validator.validate("https://nope.invalid"))
                .isInstanceOf(InvalidMonitorUrlException.class)
                .hasMessageContaining("does not resolve");
    }
}
