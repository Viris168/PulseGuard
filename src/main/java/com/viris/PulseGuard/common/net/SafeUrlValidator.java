package com.viris.PulseGuard.common.net;

import com.viris.PulseGuard.common.exception.InvalidMonitorUrlException;
import com.viris.PulseGuard.enumeration.ErrorType;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * SSRF guard for user-supplied monitor URLs (architecture.md 6, rule 6).
 *
 * <p>Called before a monitor is saved <em>and</em> before every check — DNS answers
 * change, so a host that resolved publicly at save time can point at 127.0.0.1 later
 * (DNS rebinding). Validation happens after resolution, never on the string alone, and
 * every address a host resolves to must be public.
 *
 * <p>Rejection messages never echo the resolved address, so the API cannot be used as
 * an internal-network port scanner.
 */
@Component
public class SafeUrlValidator {

    /** Seam for tests and for callers that resolve once and reuse the answer. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final Resolver resolver;

    public SafeUrlValidator() {
        this(InetAddress::getAllByName);
    }

    public SafeUrlValidator(Resolver resolver) {
        this.resolver = resolver;
    }

    public void validate(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new InvalidMonitorUrlException("not a valid URL");
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new InvalidMonitorUrlException("only http and https are supported");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidMonitorUrlException("missing host");
        }

        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new InvalidMonitorUrlException("host does not resolve", ErrorType.DNS);
        }
        if (addresses.length == 0) {
            throw new InvalidMonitorUrlException("host does not resolve", ErrorType.DNS);
        }

        // Every answer must be public: one private address is enough to reach the internal network.
        for (InetAddress address : addresses) {
            if (isPrivate(address)) {
                throw new InvalidMonitorUrlException("host resolves to a private or reserved address");
            }
        }
    }

    private boolean isPrivate(InetAddress address) {
        if (address.isLoopbackAddress()      // 127.0.0.0/8, ::1
                || address.isAnyLocalAddress()   // 0.0.0.0, ::
                || address.isLinkLocalAddress()  // 169.254.0.0/16 (incl. cloud metadata), fe80::/10
                || address.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            if (first == 0) {
                return true;                                  // 0.0.0.0/8 "this network"
            }
            if (first == 100 && second >= 64 && second <= 127) {
                return true;                                  // 100.64.0.0/10 carrier-grade NAT
            }
            return first >= 240;                              // 240.0.0.0/4 reserved, incl. broadcast
        }
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC; // fc00::/7 unique local
    }
}
