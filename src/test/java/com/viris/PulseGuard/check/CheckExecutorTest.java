package com.viris.PulseGuard.check;

import com.viris.PulseGuard.common.net.SafeUrlValidator;
import com.viris.PulseGuard.enumeration.CheckResult;
import com.viris.PulseGuard.enumeration.ErrorType;
import com.viris.PulseGuard.monitor.Monitor;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives the executor against a real socket (MockWebServer) so timeouts, refused
 * connections and status handling are exercised for real rather than stubbed.
 */
class CheckExecutorTest {

    private MockWebServer server;
    private CheckExecutor executor;
    private CheckRepository checkRepository;

    private static final CheckProperties PROPERTIES = new CheckProperties(
            Duration.ofSeconds(2), Duration.ofSeconds(1), "PulseGuard-Test/1.0", 500);

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();

        checkRepository = mock(CheckRepository.class);
        // save() returns its argument, so assertions can read the row that would be stored.
        when(checkRepository.save(any(Check.class))).thenAnswer(call -> call.getArgument(0));

        // Loopback is what MockWebServer listens on, so the real validator would (correctly)
        // reject it. Stub resolution to a public address; SSRF itself is covered below and
        // in SafeUrlValidatorTest.
        SafeUrlValidator permissive = new SafeUrlValidator(
                host -> new InetAddress[]{InetAddress.getByName("93.184.216.34")});

        executor = new CheckExecutor(
                new CheckClientConfig().checkWebClient(PROPERTIES),
                checkRepository, permissive, PROPERTIES);
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    private Monitor monitor(int expectedStatus, int timeoutMs) {
        Monitor monitor = new Monitor();
        monitor.setName("target");
        monitor.setUrl(server.url("/health").toString());
        monitor.setMethod("GET");
        monitor.setExpectedStatus(expectedStatus);
        monitor.setTimeoutMs(timeoutMs);
        return monitor;
    }

    @Test
    void recordsUpWhenTheStatusMatches() {
        server.enqueue(new MockResponse().setResponseCode(200));

        Check check = executor.execute(monitor(200, 5000));

        assertThat(check.getResult()).isEqualTo(CheckResult.UP);
        assertThat(check.getStatusCode()).isEqualTo(200);
        assertThat(check.getResponseTimeMs()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(check.getErrorType()).isNull();
        verify(checkRepository).save(check);
    }

    @Test
    void recordsStatusMismatchWhenTheCodeDiffers() {
        server.enqueue(new MockResponse().setResponseCode(500));

        Check check = executor.execute(monitor(200, 5000));

        assertThat(check.getResult()).isEqualTo(CheckResult.DOWN);
        assertThat(check.getErrorType()).isEqualTo(ErrorType.STATUS_MISMATCH);
        assertThat(check.getStatusCode()).isEqualTo(500);
        assertThat(check.getErrorMessage()).contains("Expected 200").contains("500");
    }

    @Test
    void treatsAnyExpectedStatusAsUp() {
        server.enqueue(new MockResponse().setResponseCode(204));

        assertThat(executor.execute(monitor(204, 5000)).getResult()).isEqualTo(CheckResult.UP);
    }

    @Test
    void recordsTimeoutWhenTheTargetIsTooSlowToRespond() {
        server.enqueue(new MockResponse().setResponseCode(200).setHeadersDelay(3, TimeUnit.SECONDS));

        Check check = executor.execute(monitor(200, 300)); // 300ms budget vs a 3s response

        assertThat(check.getResult()).isEqualTo(CheckResult.DOWN);
        assertThat(check.getErrorType()).isEqualTo(ErrorType.TIMEOUT);
    }

    @Test
    void aSlowBodyDoesNotFailTheCheck() {
        // The timeout bounds time-to-status; the body is discarded unread, so a target
        // that answers fast and then dribbles its body is still up.
        server.enqueue(new MockResponse().setResponseCode(200).setBodyDelay(3, TimeUnit.SECONDS));

        Check check = executor.execute(monitor(200, 500));

        assertThat(check.getResult()).isEqualTo(CheckResult.UP);
    }

    @Test
    void oneSlowTargetDoesNotOutlastItsOwnTimeout() {
        server.enqueue(new MockResponse().setResponseCode(200).setHeadersDelay(3, TimeUnit.SECONDS));

        long startedAt = System.nanoTime();
        executor.execute(monitor(200, 500));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        // Rule 1: the call returns on the monitor's 500ms budget, not the target's 3s.
        assertThat(elapsedMs).isLessThan(2_500);
    }

    @Test
    void recordsConnectionErrorWhenNothingIsListening() throws IOException {
        Monitor monitor = monitor(200, 2000);
        server.shutdown(); // the port is now refusing connections

        Check check = executor.execute(monitor);

        assertThat(check.getResult()).isEqualTo(CheckResult.DOWN);
        assertThat(check.getErrorType()).isEqualTo(ErrorType.CONNECTION);
    }

    @Test
    void sendsTheConfiguredMethodAndUserAgent() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(200));
        Monitor monitor = monitor(200, 5000);
        monitor.setMethod("HEAD");

        executor.execute(monitor);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("HEAD");
        assertThat(request.getHeader("User-Agent")).isEqualTo("PulseGuard-Test/1.0");
    }

    @Test
    void doesNotFollowRedirects() {
        // A redirect target has not been SSRF-checked, so it is reported, not followed.
        server.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", "http://169.254.169.254/latest/meta-data/"));

        Check check = executor.execute(monitor(200, 5000));

        assertThat(check.getStatusCode()).isEqualTo(302);
        assertThat(check.getErrorType()).isEqualTo(ErrorType.STATUS_MISMATCH);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void recordsDownWithoutCallingWhenTheHostIsNowPrivate() {
        // DNS rebinding: public when the monitor was saved, internal by check time.
        SafeUrlValidator strict = new SafeUrlValidator(
                host -> new InetAddress[]{InetAddress.getByName("10.0.0.1")});
        CheckExecutor guarded = new CheckExecutor(
                new CheckClientConfig().checkWebClient(PROPERTIES),
                checkRepository, strict, PROPERTIES);

        Check check = guarded.execute(monitor(200, 5000));

        assertThat(check.getResult()).isEqualTo(CheckResult.DOWN);
        assertThat(check.getErrorType()).isEqualTo(ErrorType.CONNECTION);
        assertThat(check.getErrorMessage()).doesNotContain("10.0.0.1");
        assertThat(server.getRequestCount()).isZero(); // never left the process
    }

    @Test
    void recordsDnsErrorWhenTheHostDoesNotResolve() {
        SafeUrlValidator failing = new SafeUrlValidator(host -> {
            throw new java.net.UnknownHostException(host);
        });
        CheckExecutor guarded = new CheckExecutor(
                new CheckClientConfig().checkWebClient(PROPERTIES),
                checkRepository, failing, PROPERTIES);

        Check check = guarded.execute(monitor(200, 5000));

        assertThat(check.getErrorType()).isEqualTo(ErrorType.DNS);
        verify(checkRepository).save(any(Check.class));
    }

    @Test
    void truncatesLongErrorMessages() {
        CheckProperties shortLimit = new CheckProperties(
                Duration.ofSeconds(2), Duration.ofSeconds(1), "PulseGuard-Test/1.0", 20);
        CheckExecutor limited = new CheckExecutor(
                new CheckClientConfig().checkWebClient(shortLimit),
                checkRepository, new SafeUrlValidator(
                        host -> new InetAddress[]{InetAddress.getByName("93.184.216.34")}),
                shortLimit);
        server.enqueue(new MockResponse().setResponseCode(503));

        Check check = limited.execute(monitor(200, 5000));

        assertThat(check.getErrorMessage()).hasSize(20);
    }

    @Test
    void neverThrowsForATargetFailure() {
        server.enqueue(new MockResponse().setResponseCode(500));

        // The scheduler must always get a row back, never an exception to handle.
        assertThat(executor.execute(monitor(200, 5000))).isNotNull();
        verify(checkRepository, never()).delete(any());
    }
}
