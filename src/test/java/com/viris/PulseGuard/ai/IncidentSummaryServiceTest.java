package com.viris.PulseGuard.ai;

import com.viris.PulseGuard.ai.dto.IncidentSummaryResponse;
import com.viris.PulseGuard.common.exception.IncidentNotFoundException;
import com.viris.PulseGuard.enumeration.IncidentStatus;
import com.viris.PulseGuard.incident.Incident;
import com.viris.PulseGuard.incident.IncidentQueryService;
import com.viris.PulseGuard.incident.IncidentRepository;
import com.viris.PulseGuard.incident.dto.IncidentDetailResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Caching, failure handling and tenant checks, with the model mocked: no network, no cost. */
class IncidentSummaryServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long INCIDENT_ID = 42L;
    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");
    private static final Instant STARTED = NOW.minus(Duration.ofHours(1));
    private static final Duration TTL = Duration.ofMinutes(10);

    private final IncidentQueryService queries = mock(IncidentQueryService.class);
    private final IncidentRepository incidents = mock(IncidentRepository.class);
    private final ChatModel model = mock(ChatModel.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ChatModel> modelProvider = mock(ObjectProvider.class);
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        when(modelProvider.getIfAvailable()).thenReturn(model);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void writesAndStoresASummaryWhenNoneExists() {
        stored(openIncident(null, null));
        answer("The API returned 500 errors for an hour.");

        Optional<IncidentSummaryResponse> result = service().summarize(USER_ID, INCIDENT_ID);

        assertThat(result).contains(new IncidentSummaryResponse("The API returned 500 errors for an hour.", NOW));
        verify(incidents).saveAiSummary(INCIDENT_ID, "The API returned 500 errors for an hour.", NOW);
    }

    @Test
    void stripsWhitespaceAroundTheAnswer() {
        stored(openIncident(null, null));
        answer("\n  It failed.  \n");

        assertThat(service().summarize(USER_ID, INCIDENT_ID)).get()
                .extracting(IncidentSummaryResponse::summary).isEqualTo("It failed.");
    }

    @Test
    void neverCallsTheModelForAnotherUsersIncident() {
        when(queries.detail(USER_ID, INCIDENT_ID)).thenThrow(new IncidentNotFoundException(INCIDENT_ID));

        assertThatThrownBy(() -> service().summarize(USER_ID, INCIDENT_ID))
                .isInstanceOf(IncidentNotFoundException.class);
        verifyNoInteractions(model, incidents);
    }

    @Test
    void returnsEmptyWhenNoModelIsConfigured() {
        stored(openIncident(null, null));
        when(modelProvider.getIfAvailable()).thenReturn(null);

        assertThat(service().summarize(USER_ID, INCIDENT_ID)).isEmpty();
        verify(incidents, never()).saveAiSummary(anyLong(), anyString(), any());
    }

    @Test
    void returnsEmptyAndSavesNothingWhenTheProviderFails() {
        stored(openIncident(null, null));
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("429 Too Many Requests"));

        assertThat(service().summarize(USER_ID, INCIDENT_ID)).isEmpty();
        verify(incidents, never()).saveAiSummary(anyLong(), anyString(), any());
    }

    @Test
    void returnsEmptyWhenTheModelAnswersWithBlankText() {
        stored(openIncident(null, null));
        answer("   ");

        assertThat(service().summarize(USER_ID, INCIDENT_ID)).isEmpty();
        verify(incidents, never()).saveAiSummary(anyLong(), anyString(), any());
    }

    @Test
    void returnsEmptyWhenTheModelReturnsNoResult() {
        stored(openIncident(null, null));
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of()));

        assertThat(service().summarize(USER_ID, INCIDENT_ID)).isEmpty();
    }

    @Test
    void givesUpWhenTheProviderIsTooSlow() {
        stored(openIncident(null, null));
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Thread.sleep(5_000);
            return response("Too late.");
        });
        IncidentSummaryService service = service(new AiProperties(TTL, Duration.ofMillis(100), Duration.ofSeconds(90), 500, 5, Duration.ofSeconds(10)));

        long started = System.nanoTime();
        Optional<IncidentSummaryResponse> result = service.summarize(USER_ID, INCIDENT_ID);

        assertThat(result).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        verify(incidents, never()).saveAiSummary(anyLong(), anyString(), any());
    }

    @Test
    void truncatesOverlongSummaries() {
        stored(openIncident(null, null));
        answer("y".repeat(2_000));

        String summary = service().summarize(USER_ID, INCIDENT_ID).orElseThrow().summary();

        assertThat(summary).hasSize(IncidentSummaryService.MAX_SUMMARY_LENGTH + 1).endsWith("…");
    }

    @Test
    void servesTheStoredSummaryOfAResolvedIncidentWithoutCallingTheModel() {
        Instant resolvedAt = NOW.minus(Duration.ofDays(3));
        Instant writtenAt = resolvedAt.plusSeconds(30);
        stored(resolvedIncident(resolvedAt, "Old but final.", writtenAt));

        assertThat(service().summarize(USER_ID, INCIDENT_ID))
                .contains(new IncidentSummaryResponse("Old but final.", writtenAt));
        verifyNoInteractions(model);
    }

    @Test
    void rewritesASummaryWrittenBeforeTheIncidentResolved() {
        Instant resolvedAt = NOW.minus(Duration.ofMinutes(1));
        stored(resolvedIncident(resolvedAt, "Still down.", resolvedAt.minusSeconds(60)));
        answer("It recovered.");

        assertThat(service().summarize(USER_ID, INCIDENT_ID)).get()
                .extracting(IncidentSummaryResponse::summary).isEqualTo("It recovered.");
        verify(incidents).saveAiSummary(INCIDENT_ID, "It recovered.", NOW);
    }

    @Test
    void servesAnOpenIncidentsSummaryYoungerThanTheTtl() {
        Instant writtenAt = NOW.minus(TTL).plusSeconds(1);
        stored(openIncident("Down for 50 minutes.", writtenAt));

        assertThat(service().summarize(USER_ID, INCIDENT_ID))
                .contains(new IncidentSummaryResponse("Down for 50 minutes.", writtenAt));
        verifyNoInteractions(model);
    }

    @Test
    void rewritesAnOpenIncidentsSummaryOnceTheTtlHasPassed() {
        stored(openIncident("Down for 50 minutes.", NOW.minus(TTL)));
        answer("Down for an hour.");

        assertThat(service().summarize(USER_ID, INCIDENT_ID)).get()
                .extracting(IncidentSummaryResponse::summary).isEqualTo("Down for an hour.");
        verify(model).call(any(Prompt.class));
    }

    @Test
    void sendsTheIncidentFactsButNotTheUrl() {
        stored(openIncident(null, null));
        answer("Fine.");

        service().summarize(USER_ID, INCIDENT_ID);

        verify(model).call(org.mockito.ArgumentMatchers.<Prompt>argThat(prompt -> {
            String facts = prompt.getInstructions().get(1).getText();
            return facts.contains("Monitor: Shop API") && !facts.contains("api.example.com");
        }));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private IncidentSummaryService service() {
        return service(new AiProperties(TTL, Duration.ofSeconds(5), Duration.ofSeconds(90), 500, 5, Duration.ofSeconds(10)));
    }

    private IncidentSummaryService service(AiProperties properties) {
        return new IncidentSummaryService(queries, incidents, new ModelCaller(modelProvider, properties, executor),
                properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** The incident as the owner's detail query and the cache lookup both see it. */
    private void stored(Incident incident) {
        IncidentDetailResponse detail = new IncidentDetailResponse(INCIDENT_ID, 10L, "Shop API",
                incident.getStatus(), "HTTP 500: got 500", incident.getStartedAt(), incident.getResolvedAt(),
                "HTTP", "https://api.example.com/health", "GET", 60, List.of());
        when(queries.detail(USER_ID, INCIDENT_ID)).thenReturn(detail);
        when(incidents.findById(INCIDENT_ID)).thenReturn(Optional.of(incident));
    }

    private void answer(String text) {
        when(model.call(any(Prompt.class))).thenReturn(response(text));
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static Incident openIncident(String summary, Instant summaryAt) {
        Incident incident = new Incident();
        incident.setId(INCIDENT_ID);
        incident.setStatus(IncidentStatus.OPEN);
        incident.setStartedAt(STARTED);
        incident.setAiSummary(summary);
        incident.setAiSummaryAt(summaryAt);
        return incident;
    }

    private static Incident resolvedIncident(Instant resolvedAt, String summary, Instant summaryAt) {
        Incident incident = openIncident(summary, summaryAt);
        incident.setStatus(IncidentStatus.RESOLVED);
        incident.setStartedAt(resolvedAt.minus(Duration.ofMinutes(20)));
        incident.setResolvedAt(resolvedAt);
        return incident;
    }
}
