package com.viris.PulseGuard.monitor.dto;

import com.viris.PulseGuard.enumeration.MonitorType;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

/**
 * HTTP monitors need a URL, method, expected status and timeout; heartbeats need a grace
 * period and ignore the rest. Each violation is attached to its field, so the response reads
 * exactly like field-level validation ({@code fieldErrors.url}, ...).
 */
public class MonitorRequestValidator implements ConstraintValidator<ValidMonitorRequest, MonitorRequest> {

    static final int MAX_HEARTBEAT_PERIOD = 30 * 86_400;
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

        if (r.expectedStatus() == null) {
            valid = fail(context, "expectedStatus", "Expected status code is required");
        } else if (r.expectedStatus() < 100 || r.expectedStatus() > 599) {
            valid = fail(context, "expectedStatus", "Expected status must be between 100 and 599");
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
