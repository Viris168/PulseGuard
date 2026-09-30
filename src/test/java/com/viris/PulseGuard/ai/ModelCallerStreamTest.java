package com.viris.PulseGuard.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** ModelCaller.stream with a fake streaming model: order, usage, timeouts, failures, Stop. */
class ModelCallerStreamTest {

    private static final Prompt PROMPT = new Prompt(List.of(new UserMessage("Is anything down?")));

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void emitsThePiecesInOrderThenFinishedWithTheTokenUsage() {
        ModelCaller caller = caller(() -> Flux.just(
                piece("Health is"), piece(" down:"), piece(" it returns 503."), last(" ", "gemini-3.1-flash-lite", 812, 41)));

        List<ModelStreamEvent> events = caller.stream(PROMPT, "test").collectList().block();

        assertThat(events).containsExactly(
                new ModelStreamEvent.Text("Health is"),
                new ModelStreamEvent.Text(" down:"),
                new ModelStreamEvent.Text(" it returns 503."),
                new ModelStreamEvent.Text(" "),
                new ModelStreamEvent.Finished("gemini-3.1-flash-lite", 812, 41));
    }

    @Test
    void skipsEmptyPieces() {
        ModelCaller caller = caller(() -> Flux.just(piece(""), piece("Hi"), piece(null)));

        List<ModelStreamEvent> events = caller.stream(PROMPT, "test").collectList().block();

        assertThat(events).containsExactly(new ModelStreamEvent.Text("Hi"), new ModelStreamEvent.Finished(null, null, null));
    }

    @Test
    void reportsUnknownUsageAsNullRatherThanZero() {
        ModelCaller caller = caller(() -> Flux.just(last("Hi", "", 0, 0)));

        List<ModelStreamEvent> events = caller.stream(PROMPT, "test").collectList().block();

        assertThat(events).last().isEqualTo(new ModelStreamEvent.Finished(null, null, null));
    }

    @Test
    void givesUpWhenTheFirstPieceIsTooSlow() {
        ModelCaller caller = caller(Flux::never, Duration.ofMillis(100), Duration.ofSeconds(10));

        long started = System.nanoTime();
        assertThatThrownBy(() -> caller.stream(PROMPT, "test").blockLast())
                .isInstanceOfSatisfying(ModelStreamException.class, e -> assertThat(e.isTimedOut()).isTrue());
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void stopsAnAnswerThatRunsPastTheOverallDeadline() {
        // A piece every 50 ms forever: each arrives in time, but the whole answer never ends.
        ModelCaller caller = caller(() -> Flux.interval(Duration.ofMillis(50)).map(i -> piece("word ")),
                Duration.ofSeconds(1), Duration.ofMillis(300));
        AtomicInteger pieces = new AtomicInteger();

        assertThatThrownBy(() -> caller.stream(PROMPT, "test").doOnNext(e -> pieces.incrementAndGet()).blockLast())
                .isInstanceOfSatisfying(ModelStreamException.class, e -> assertThat(e.isTimedOut()).isTrue());
        assertThat(pieces.get()).isBetween(2, 10);
    }

    @Test
    void turnsProviderErrorsIntoASafeMessage() {
        ModelCaller caller = caller(() -> Flux.error(new IllegalStateException("429 for key AIzaSecret, prompt: <data>…")));

        assertThatThrownBy(() -> caller.stream(PROMPT, "test").blockLast())
                .isInstanceOfSatisfying(ModelStreamException.class, e -> {
                    assertThat(e.isTimedOut()).isFalse();
                    assertThat(e.getMessage()).isEqualTo("The AI model failed to answer").doesNotContain("AIza");
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test
    void keepsThePiecesThatArrivedBeforeAFailure() {
        ModelCaller caller = caller(() -> Flux.concat(Flux.just(piece("Health is")),
                Flux.error(new IllegalStateException("connection reset"))));
        AtomicInteger pieces = new AtomicInteger();

        assertThatThrownBy(() -> caller.stream(PROMPT, "test").doOnNext(e -> pieces.incrementAndGet()).blockLast())
                .isInstanceOf(ModelStreamException.class);
        assertThat(pieces).hasValue(1);
    }

    @Test
    void stoppingCancelsTheProviderCall() {
        AtomicBoolean cancelled = new AtomicBoolean();
        ModelCaller caller = caller(() -> Flux.interval(Duration.ofMillis(20)).map(i -> piece("word "))
                .doOnCancel(() -> cancelled.set(true)));

        // take(2) is what Stop does: the subscriber stops listening after a couple of pieces.
        List<ModelStreamEvent> events = caller.stream(PROMPT, "test").take(2).collectList().block();

        assertThat(events).hasSize(2);
        assertThat(cancelled).isTrue();
    }

    @Test
    void doesNothingUntilSubscribed() {
        AtomicInteger calls = new AtomicInteger();
        ModelCaller caller = caller(() -> {
            calls.incrementAndGet();
            return Flux.just(piece("Hi"));
        });

        Flux<ModelStreamEvent> stream = caller.stream(PROMPT, "test");
        assertThat(calls).hasValue(0);

        stream.blockLast();
        assertThat(calls).hasValue(1);
    }

    @Test
    void failsClearlyWhenAiIsOff() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> none = mock(ObjectProvider.class);
        ModelCaller caller = new ModelCaller(none, properties(Duration.ofSeconds(1), Duration.ofSeconds(1)), executor);

        assertThatThrownBy(() -> caller.stream(PROMPT, "test").blockLast())
                .isInstanceOf(ModelStreamException.class).hasMessage("No AI model is configured");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private ModelCaller caller(Supplier<Flux<ChatResponse>> stream) {
        return caller(stream, Duration.ofSeconds(2), Duration.ofSeconds(10));
    }

    private ModelCaller caller(Supplier<Flux<ChatResponse>> stream, Duration firstPiece, Duration whole) {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException("streaming only");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(stream);
            }
        };
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        return new ModelCaller(provider, properties(firstPiece, whole), executor);
    }

    private static AiProperties properties(Duration firstPiece, Duration whole) {
        return new AiProperties(Duration.ofMinutes(10), firstPiece, whole, 500, 5, Duration.ofSeconds(10));
    }

    private static ChatResponse piece(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** The provider's final chunk: the last text plus the totals for the whole answer. */
    private static ChatResponse last(String text, String model, int inputTokens, int outputTokens) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().model(model).usage(new DefaultUsage(inputTokens, outputTokens)).build());
    }
}
