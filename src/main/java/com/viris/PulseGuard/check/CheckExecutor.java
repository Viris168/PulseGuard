package com.viris.PulseGuard.check;

import com.viris.PulseGuard.common.exception.InvalidMonitorUrlException;
import com.viris.PulseGuard.common.net.SafeUrlValidator;
import com.viris.PulseGuard.enumeration.CheckResult;
import com.viris.PulseGuard.enumeration.ErrorType;
import com.viris.PulseGuard.monitor.Monitor;
import io.netty.handler.timeout.ReadTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClientRequest;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import javax.net.ssl.SSLException;

/**
 * Runs one HTTP check against a monitor's target and records the outcome in {@code checks}.
 *
 * <p>Blocking by design: the caller is a scheduler job, and bounding concurrency is the
 * scheduler's thread pool's job (rule 1 — one slow endpoint must never block others, which
 * the per-monitor timeouts below guarantee).
 *
 * <p>The monitor's {@code timeoutMs} bounds time-to-response-status, and the recorded
 * response time measures the same thing: the body is released unread, because uptime
 * depends on the status code, and downloading a large body on every interval would cost
 * bandwidth for nothing.
 *
 * <p>Redirects are deliberately <em>not</em> followed. A 302 to {@code 169.254.169.254}
 * would sail past the SSRF check that was done on the original host, so a redirect is
 * reported as the status code it is.
 */
@Component
public class CheckExecutor {

    private static final Logger log = LoggerFactory.getLogger(CheckExecutor.class);

    private final WebClient webClient;
    private final CheckRepository checkRepository;
    private final SafeUrlValidator urlValidator;
    private final CheckProperties properties;

    public CheckExecutor(WebClient checkWebClient,
                         CheckRepository checkRepository,
                         SafeUrlValidator urlValidator,
                         CheckProperties properties) {
        this.webClient = checkWebClient;
        this.checkRepository = checkRepository;
        this.urlValidator = urlValidator;
        this.properties = properties;
    }

    /** Performs the check and saves the resulting row. Never throws for a target's failure. */
    public Check execute(Monitor monitor) {
        Duration responseTimeout = Duration.ofMillis(monitor.getTimeoutMs());
        // Ceiling on the whole exchange, so a thread cannot hang past connect + response.
        Duration hardCap = responseTimeout
                .plus(properties.connectTimeout())
                .plus(properties.graceTimeout());

        // Rule 6: re-resolve every time. DNS answers change, so a host that was public when
        // the monitor was saved can point inside the network by now.
        try {
            urlValidator.validate(monitor.getUrl());
        } catch (InvalidMonitorUrlException e) {
            log.warn("Blocked check for monitorId={}: {}", monitor.getId(), e.getMessage());
            return save(failure(monitor, e.getErrorType(), e.getMessage(), null));
        }

        long startedAt = System.nanoTime();
        try {
            WebClient.RequestBodySpec request = webClient
                    .method(HttpMethod.valueOf(monitor.getMethod()))
                    .uri(monitor.getUrl())
                    .headers(headers -> {
                        // The user's headers first; User-Agent is ours (the validator refuses it).
                        monitor.getHeaders().forEach(h -> headers.set(h.getName(), h.getValue()));
                        headers.set("User-Agent", properties.userAgent());
                    })
                    .httpRequest(r -> {
                        HttpClientRequest nativeRequest = r.getNativeRequest();
                        nativeRequest.responseTimeout(responseTimeout);
                    });
            if (monitor.getRequestBody() != null && sendsBody(monitor.getMethod())) {
                if (monitor.getHeaders().stream().noneMatch(h -> h.getName().equalsIgnoreCase("Content-Type"))) {
                    request.contentType(guessContentType(monitor.getRequestBody()));
                }
                request.bodyValue(monitor.getRequestBody());
            }
            Integer statusCode = request
                    // exchangeToMono, not retrieve(): a 4xx/5xx is data here, not an exception.
                    .exchangeToMono(response -> response.releaseBody()
                            .thenReturn(response.statusCode().value()))
                    .block(hardCap);

            int elapsedMs = elapsedMs(startedAt);
            if (statusCode == null) {
                return save(failure(monitor, ErrorType.TIMEOUT, "No response", elapsedMs));
            }
            if (!monitor.getExpectedStatuses().contains(statusCode)) {
                return save(failure(monitor, ErrorType.STATUS_MISMATCH,
                        "Expected " + describe(monitor.getExpectedStatuses()) + " but got " + statusCode,
                        elapsedMs, statusCode));
            }

            Check check = newCheck(monitor);
            check.setResult(CheckResult.UP);
            check.setStatusCode(statusCode);
            check.setResponseTimeMs(elapsedMs);
            log.debug("Check UP for monitorId={} status={} in {}ms", monitor.getId(), statusCode, elapsedMs);
            return save(check);

        } catch (Exception e) {
            ErrorType errorType = classify(e);
            log.info("Check DOWN for monitorId={} type={}", monitor.getId(), errorType);
            return save(failure(monitor, errorType, rootMessage(e), elapsedMs(startedAt)));
        }
    }

    private static boolean sendsBody(String method) {
        return "POST".equals(method) || "PUT".equals(method);
    }

    /** When the user set no Content-Type: JSON if it looks like JSON, plain text otherwise. */
    static MediaType guessContentType(String body) {
        String trimmed = body.stripLeading();
        return trimmed.startsWith("{") || trimmed.startsWith("[")
                ? MediaType.APPLICATION_JSON
                : new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);
    }

    /** "200", "200 or 204", "200, 201 or 204": the incident cause reads as a sentence. */
    static String describe(List<Integer> statuses) {
        if (statuses.size() == 1) {
            return String.valueOf(statuses.getFirst());
        }
        String head = statuses.subList(0, statuses.size() - 1).stream()
                .map(String::valueOf).collect(Collectors.joining(", "));
        return head + " or " + statuses.getLast();
    }

    /**
     * Maps a transport failure onto {@link ErrorType}. The cause chain is walked because
     * WebClient wraps the real cause in {@code WebClientRequestException}.
     */
    private ErrorType classify(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnknownHostException) {
                return ErrorType.DNS;
            }
            if (cause instanceof SSLException) {
                return ErrorType.SSL;
            }
            if (cause instanceof ReadTimeoutException
                    || cause instanceof TimeoutException
                    || cause instanceof IllegalStateException) {
                // block(hardCap) expiring raises IllegalStateException, not TimeoutException.
                return ErrorType.TIMEOUT;
            }
            if (cause instanceof ConnectException) {
                return ErrorType.CONNECTION;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return ErrorType.CONNECTION;
    }

    private Check failure(Monitor monitor, ErrorType type, String message, Integer elapsedMs) {
        return failure(monitor, type, message, elapsedMs, null);
    }

    private Check failure(Monitor monitor, ErrorType type, String message,
                          Integer elapsedMs, Integer statusCode) {
        Check check = newCheck(monitor);
        check.setResult(CheckResult.DOWN);
        check.setErrorType(type);
        check.setErrorMessage(truncate(message));
        check.setResponseTimeMs(elapsedMs);
        check.setStatusCode(statusCode);
        return check;
    }

    private Check newCheck(Monitor monitor) {
        Check check = new Check();
        check.setMonitor(monitor);
        check.setCheckedAt(Instant.now());
        return check;
    }

    private Check save(Check check) {
        return checkRepository.save(check);
    }

    private int elapsedMs(long startedAtNanos) {
        return (int) Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }

    private String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        int max = properties.maxErrorLength();
        return message.length() <= max ? message : message.substring(0, max);
    }
}
