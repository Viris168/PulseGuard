package com.viris.PulseGuard.monitor.dto;

import com.viris.PulseGuard.enumeration.MonitorType;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * HTTP monitors need a URL, method, expected status and timeout; heartbeats need a grace
 * period and ignore the rest. Each violation is attached to its field, so the response reads
 * exactly like field-level validation ({@code fieldErrors.url}, ...).
 */
public class MonitorRequestValidator implements ConstraintValidator<ValidMonitorRequest, MonitorRequest> {

    static final int MAX_HEARTBEAT_PERIOD = 30 * 86_400;
    static final int MAX_STATUSES = 10;
    static final int MAX_HEADERS = 20;
    static final int MAX_HEADER_VALUE = 2000;
    static final int MAX_BODY = 10_000;

    /** RFC 9110 token characters. */
    private static final Pattern HEADER_NAME = Pattern.compile("^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,100}$");
    /**
     * Framing and routing headers the HTTP client must own: letting a user set Host or
     * Content-Length could send the check somewhere other than the URL the SSRF check cleared,
     * or produce a malformed request. User-Agent stays ours so targets can allow-list PulseGuard.
     */
    private static final Set<String> FORBIDDEN_HEADERS = Set.of("host", "content-length", "transfer-encoding",
            "connection", "keep-alive", "proxy-connection", "te", "trailer", "upgrade", "expect", "user-agent");
    static final int MAX_GRACE = 7 * 86_400;

    private static final Pattern URL = Pattern.compile("^https?://.+");
    private static final Pattern METHOD = Pattern.compile("GET|POST|PUT|HEAD");

    @Override
    public boolean isValid(MonitorRequest request, ConstraintValidatorContext context) {
        if (request == null) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        return request.typeOrDefault() == MonitorType.HEARTBEAT
                ? validHeartbeat(request, context)
                : validHttp(request, context);
    }

    private static boolean validHttp(MonitorRequest r, ConstraintValidatorContext context) {
        boolean valid = true;
        if (r.url() == null || r.url().isBlank()) {
            valid = fail(context, "url", "URL is required");
        } else if (r.url().length() > 2048) {
            valid = fail(context, "url", "URL must be 2048 characters or fewer");
        } else if (!URL.matcher(r.url()).matches()) {
            valid = fail(context, "url", "URL must start with http:// or https://");
        }

        if (r.method() == null || r.method().isBlank()) {
            valid = fail(context, "method", "Method is required");
        } else if (!METHOD.matcher(r.method()).matches()) {
            valid = fail(context, "method", "Method must be GET, POST, PUT or HEAD");
        }

        List<Integer> statuses = r.statusesOrDefault();
        if (statuses.isEmpty()) {
            valid = fail(context, "expectedStatuses", "Expected status code is required");
        } else if (statuses.size() > MAX_STATUSES) {
            valid = fail(context, "expectedStatuses", "Accept at most " + MAX_STATUSES + " status codes");
        } else if (statuses.stream().anyMatch(s -> s == null || s < 100 || s > 599)) {
            valid = fail(context, "expectedStatuses", "Status codes must be between 100 and 599");
        } else if (new HashSet<>(statuses).size() != statuses.size()) {
            valid = fail(context, "expectedStatuses", "Each status code can appear only once");
        }

        String headersError = headersError(r.headersOrEmpty());
        if (headersError != null) {
            valid = fail(context, "headers", headersError);
        }

        if (r.requestBody() != null && !r.requestBody().isBlank()) {
            if (r.requestBody().length() > MAX_BODY) {
                valid = fail(context, "requestBody", "Keep the body under " + MAX_BODY + " characters");
            } else if (!"POST".equals(r.method()) && !"PUT".equals(r.method())) {
                valid = fail(context, "requestBody", "Only POST and PUT checks can send a body");
            }
        }

        if (r.timeoutMs() == null) {
            valid = fail(context, "timeoutMs", "Timeout is required");
        } else if (r.timeoutMs() < 1000) {
            valid = fail(context, "timeoutMs", "Timeout must be at least 1000 ms");
        } else if (r.timeoutMs() > 30000) {
            valid = fail(context, "timeoutMs", "Timeout must not exceed 30000 ms");
        }
        return valid;
    }

    /** The first problem with the header list, or null. Values may be null (keep the saved one). */
    private static String headersError(List<HeaderInput> headers) {
        if (headers.size() > MAX_HEADERS) {
            return "Send at most " + MAX_HEADERS + " headers";
        }
        Set<String> seen = new HashSet<>();
        for (HeaderInput header : headers) {
            if (header == null || header.name() == null || !HEADER_NAME.matcher(header.name().trim()).matches()) {
                return "Header names can only use letters, digits and - (e.g. X-Api-Key)";
            }
            String name = header.name().trim().toLowerCase(Locale.ROOT);
            if (FORBIDDEN_HEADERS.contains(name)) {
                return "The " + header.name().trim() + " header is set by PulseGuard and can't be changed";
            }
            if (!seen.add(name)) {
                return "The " + header.name().trim() + " header appears twice";
            }
            String value = header.value();
            if (value != null) {
                if (value.length() > MAX_HEADER_VALUE) {
                    return "Header values must be " + MAX_HEADER_VALUE + " characters or fewer";
                }
                // CR or LF would let a value start a new header or end the request early.
                if (value.chars().anyMatch(c -> c == '\r' || c == '\n' || c == 0)) {
                    return "Header values can't contain line breaks";
                }
            }
        }
        return null;
    }

    private static boolean validHeartbeat(MonitorRequest r, ConstraintValidatorContext context) {
        boolean valid = true;
        if (r.graceSeconds() == null) {
            valid = fail(context, "graceSeconds", "Grace period is required");
        } else if (r.graceSeconds() < 0 || r.graceSeconds() > MAX_GRACE) {
            valid = fail(context, "graceSeconds", "Grace period must be between 0 and 7 days");
        }
        if (r.intervalSeconds() != null && r.intervalSeconds() > MAX_HEARTBEAT_PERIOD) {
            valid = fail(context, "intervalSeconds", "Expect a ping at least every 30 days");
        }
        return valid;
    }

    private static boolean fail(ConstraintValidatorContext context, String field, String message) {
        context.buildConstraintViolationWithTemplate(message).addPropertyNode(field).addConstraintViolation();
        return false;
    }
}
