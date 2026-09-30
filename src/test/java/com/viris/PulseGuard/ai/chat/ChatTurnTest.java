package com.viris.PulseGuard.ai.chat;

import com.viris.PulseGuard.ai.AiProperties;
import com.viris.PulseGuard.ai.AiQuotaPolicy;
import com.viris.PulseGuard.ai.ModelStreamEvent;
import com.viris.PulseGuard.ai.ModelStreamException;
import com.viris.PulseGuard.ai.chat.dto.ChatStreamEvents;
import com.viris.PulseGuard.ai.dto.AiQuotaResponse;
import com.viris.PulseGuard.ai.tools.ToolGuard;
import com.viris.PulseGuard.ai.tools.ToolRun;
import com.viris.PulseGuard.enumeration.MessageStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every way an answer can end, with the model and the browser both faked: the model is a stream
 * we push pieces into, the browser a list of the events it received.
 */
class ChatTurnTest {

    private static final Long USER = 7L;
    private static final Long CHAT = 42L;
    private static final Long QUESTION = 100L;
    private static final AiQuotaResponse QUOTA = new AiQuotaResponse(3, 5);
    private static final ModelStreamEvent.Finished USAGE = new ModelStreamEvent.Finished("gemini", 800, 40);

    private final ChatMessageStore store = mock(ChatMessageStore.class);
    private final AiQuotaPolicy quota = mock(AiQuotaPolicy.class);
    private final FakeBrowser browser = new FakeBrowser();

    @BeforeEach
    void setUp() {
        when(store.saveAnswer(any(), anyString(), any(), any(), any())).thenReturn(101L);
        when(quota.status(USER)).thenReturn(QUOTA);
    }

    @Test
    void forwardsEachPieceThenSavesTheCompleteAnswerAndSendsDone() {
        start(Flux.just(text("Health is"), text(" down."), USAGE));

        assertThat(browser.events).containsExactly(
                new ChatStreamEvents.Delta("Health is"),
                new ChatStreamEvents.Delta(" down."),
                new ChatStreamEvents.Done(QUESTION, 101L, MessageStatus.COMPLETE, QUOTA));
        verify(store).saveAnswer(CHAT, "Health is down.", MessageStatus.COMPLETE, USAGE, List.of());
        assertThat(browser.closed).isTrue();
        verify(quota, never()).release(any());
    }

    @Test
    void stopKeepsTheTextSoFarAndCancelsTheModel() {
        Sinks.Many<ModelStreamEvent> model = Sinks.many().unicast().onBackpressureBuffer();
        AtomicBoolean cancelled = new AtomicBoolean();
        ChatTurn turn = start(model.asFlux().doOnCancel(() -> cancelled.set(true)));

        model.tryEmitNext(text("It means the"));
        turn.stop();
        model.tryEmitNext(text(" server")); // arrives after Stop: dropped

        verify(store).saveAnswer(CHAT, "It means the", MessageStatus.PARTIAL, null, List.of());
        assertThat(cancelled).isTrue();
        assertThat(browser.events).containsExactly(new ChatStreamEvents.Delta("It means the"));
        verify(quota, never()).release(any()); // a stopped answer still counts
    }

    @Test
    void aClosedTabIsTreatedLikeStop() {
        browser.goneAfter = 1; // the second delta fails: the connection is gone
        Sinks.Many<ModelStreamEvent> model = Sinks.many().unicast().onBackpressureBuffer();
        AtomicBoolean cancelled = new AtomicBoolean();
        start(model.asFlux().doOnCancel(() -> cancelled.set(true)));

        model.tryEmitNext(text("One"));
        model.tryEmitNext(text(" two"));
        model.tryEmitNext(text(" three"));
        model.tryEmitComplete();

        verify(store, times(1)).saveAnswer(any(), anyString(), any(), any(), any());
        verify(store).saveAnswer(CHAT, "One two", MessageStatus.PARTIAL, null, List.of());
        assertThat(cancelled).isTrue();
    }

    @Test
    void aFailureBeforeAnyTextSavesFailedAndHandsTheQuestionBack() {
        start(Flux.error(new RuntimeException("boom")));

        verify(store).saveAnswer(CHAT, "", MessageStatus.FAILED, null, List.of());
        verify(quota).release(USER);
        assertThat(browser.events).containsExactly(new ChatStreamEvents.Error(ChatTurn.FAILED_MESSAGE, false, null));
        assertThat(browser.closed).isTrue();
    }

    @Test
    void saysSoWhenTheModelTookTooLong() {
        start(Flux.error(ModelStreamException.timedOut()));

        assertThat(browser.events).containsExactly(new ChatStreamEvents.Error(ChatTurn.TIMED_OUT_MESSAGE, false, null));
        verify(quota).release(USER);
    }

    @Test
    void aFailureAfterSomeTextKeepsItAsPartialAndStillCounts() {
        start(Flux.concat(Flux.just(text("Health is")), Flux.error(new RuntimeException("reset"))));

        verify(store).saveAnswer(CHAT, "Health is", MessageStatus.PARTIAL, null, List.of());
        verify(quota, never()).release(any());
        assertThat(browser.events).containsExactly(
                new ChatStreamEvents.Delta("Health is"),
                new ChatStreamEvents.Error(ChatTurn.CUT_OFF_MESSAGE, true, 101L));
    }

    @Test
    void anEmptyAnswerCountsAsFailed() {
        start(Flux.just(USAGE));

        verify(store).saveAnswer(CHAT, "", MessageStatus.FAILED, USAGE, List.of());
        verify(quota).release(USER);
    }

    @Test
    void savesOnlyOnceHoweverItEnds() {
        ChatTurn turn = start(Flux.just(text("Done."), USAGE));

        turn.stop(); // the browser's connection closing after "done" also calls stop

        verify(store, times(1)).saveAnswer(any(), anyString(), any(), any(), any());
        verify(store).saveAnswer(eq(CHAT), eq("Done."), eq(MessageStatus.COMPLETE), eq(USAGE), eq(List.of()));
    }

    @Test
    void stopsKeepingTextPastTheMaximumLength() {
        String chunk = "x".repeat(1000);
        start(Flux.just(text(chunk), text(chunk), text(chunk), text(chunk), text(chunk), text(chunk)));

        verify(store).saveAnswer(eq(CHAT), eq(chunk.repeat(4)), eq(MessageStatus.COMPLETE), any(), eq(List.of()));
    }

    @Test
    void showsEachLookupBeforeTheTextItLeadsToAndSavesItWithTheAnswer() {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        ToolRun run = new ToolGuard(new AiProperties(Duration.ofMinutes(10), Duration.ofSeconds(2), Duration.ofSeconds(10),
                500, 5, Duration.ofSeconds(1)), executor).start();
        ToolCallback uptime = run.guard(List.of(tool("get_uptime", "Health: 99.82% up"))).getFirst();
        // What the model loop does: the model asks, the tool runs, then the answer streams.
        Flux<ModelStreamEvent> model = Flux.defer(() -> {
            uptime.call("{\"monitor\":\"Health\",\"from\":\"2026-09-01\",\"to\":\"2026-09-03\"}", null);
            return Flux.just(text("Health was up 99.82%."), USAGE);
        });

        new ChatTurn(model, store, quota, USER, CHAT, QUESTION, run).start(browser);

        assertThat(browser.events).containsExactly(
                new ChatStreamEvents.Tool("Checked uptime for Health, 2026-09-01 to 2026-09-03"),
                new ChatStreamEvents.Delta("Health was up 99.82%."),
                new ChatStreamEvents.Done(QUESTION, 101L, MessageStatus.COMPLETE, QUOTA));
        verify(store).saveAnswer(eq(CHAT), eq("Health was up 99.82%."), eq(MessageStatus.COMPLETE), eq(USAGE),
                argThat(lookups -> lookups.size() == 1 && lookups.getFirst().tool().equals("get_uptime")
                        && lookups.getFirst().ok()));
        executor.shutdownNow();
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static ToolCallback tool(String name, String result) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("test").inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                return result;
            }
        };
    }

    private ChatTurn start(Flux<ModelStreamEvent> model) {
        ChatTurn turn = new ChatTurn(model, store, quota, USER, CHAT, QUESTION);
        turn.start(browser);
        return turn;
    }

    private static ModelStreamEvent text(String text) {
        return new ModelStreamEvent.Text(text);
    }

    /** The browser's end of the stream. {@code goneAfter}: deltas accepted before the connection drops. */
    static class FakeBrowser implements ChatEvents {
        final List<Object> events = new CopyOnWriteArrayList<>();
        volatile boolean closed;
        int goneAfter = Integer.MAX_VALUE;

        @Override
        public void delta(ChatStreamEvents.Delta delta) throws IOException {
            if (events.size() >= goneAfter) {
                throw new IOException("Broken pipe");
            }
            events.add(delta);
        }

        @Override
        public void tool(ChatStreamEvents.Tool tool) {
            events.add(tool);
        }

        @Override
        public void done(ChatStreamEvents.Done done) {
            events.add(done);
        }

        @Override
        public void error(ChatStreamEvents.Error error) {
            events.add(error);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
