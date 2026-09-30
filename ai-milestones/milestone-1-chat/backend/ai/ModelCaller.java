package com.viris.PulseGuard.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The one place that calls the configured chat model. Every AI feature goes through it, so all
 * of them share the same rules: capped at {@code pulseguard.ai.timeout} whatever the provider
 * (not every Spring AI provider has a client timeout of its own), never throws, and logs token
 * usage but never the prompt or the provider's error text, which can echo customer data.
 */
@Component
public class ModelCaller {

    private static final Logger log = LoggerFactory.getLogger(ModelCaller.class);

    private final ObjectProvider<ChatModel> chatModel;
    private final AiProperties properties;
    private final ExecutorService executor;

    public ModelCaller(ObjectProvider<ChatModel> chatModel, AiProperties properties,
                       @Qualifier("aiCallExecutor") ExecutorService executor) {
        this.chatModel = chatModel;
        this.properties = properties;
        this.executor = executor;
    }

    /** False when {@code spring.ai.model.chat=none}: AI is switched off. */
    public boolean isAvailable() {
        return chatModel.getIfAvailable() != null;
    }

    /**
     * The model's answer, stripped, or empty when AI is off, the provider failed, missed the
     * deadline, or answered with no text. {@code subject} names the call in logs, e.g.
     * {@code "summary incidentId=42"}.
     */
    public Optional<String> call(Prompt prompt, String subject) {
        ChatModel model = chatModel.getIfAvailable();
        if (model == null) {
            return Optional.empty();
        }
        Future<ChatResponse> call = executor.submit(() -> model.call(prompt));
        ChatResponse response;
        try {
            response = call.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            log.warn("AI {} timed out after {}", subject, properties.timeout());
            return Optional.empty();
        } catch (ExecutionException e) {
            log.warn("AI {} failed: {}", subject, e.getCause().getClass().getSimpleName());
            return Optional.empty();
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        String text = text(response);
        if (text.isEmpty()) {
            log.warn("AI {} came back empty", subject);
            return Optional.empty();
        }
        logUsage(subject, response);
        return Optional.of(text);
    }

    /**
     * The model's answer as it is written: {@link ModelStreamEvent.Text} pieces, then one
     * {@link ModelStreamEvent.Finished} with the token usage. Nothing runs until subscribed, and
     * cancelling the subscription (the person pressed Stop) cancels the provider call too.
     *
     * <p>The same rules as {@link #call}, as a stream: the first piece must arrive within
     * {@code pulseguard.ai.timeout} and the whole answer within {@code pulseguard.ai.stream-timeout};
     * any failure ends the stream with a {@link ModelStreamException} whose message is safe to
     * show, and the prompt, the answer and the provider's error text are never logged.
     */
    public Flux<ModelStreamEvent> stream(Prompt prompt, String subject) {
        return Flux.defer(() -> {
            ChatModel model = chatModel.getIfAvailable();
            if (model == null) {
                return Flux.error(ModelStreamException.notConfigured());
            }
            Instant deadline = Instant.now().plus(properties.streamTimeout());
            // Each response carries metadata; the last one seen holds the totals for the answer.
            AtomicReference<ChatResponse> last = new AtomicReference<>();

            Flux<ModelStreamEvent> pieces = model.stream(prompt)
                    // First piece within the timeout; every later piece before the overall deadline.
                    .timeout(Mono.delay(properties.timeout()), piece -> Mono.delay(untilDeadline(deadline)))
                    .doOnNext(response -> {
                        if (response != null && response.getMetadata() != null) {
                            last.set(response);
                        }
                    })
                    // Unstripped: the spaces between pieces are part of the answer.
                    .map(ModelCaller::rawText)
                    .filter(text -> !text.isEmpty())
                    .map(ModelStreamEvent.Text::new);

            return pieces
                    .concatWith(Mono.fromSupplier(() -> finished(subject, last.get())))
                    .onErrorMap(e -> !(e instanceof ModelStreamException), e -> {
                        if (e instanceof TimeoutException) {
                            log.warn("AI {} stream timed out", subject);
                            return ModelStreamException.timedOut();
                        }
                        log.warn("AI {} stream failed: {}", subject, e.getClass().getSimpleName());
                        return ModelStreamException.failed();
                    })
                    .doOnCancel(() -> log.info("AI {} stream stopped before the end", subject));
        });
    }

    private static Duration untilDeadline(Instant deadline) {
        Duration left = Duration.between(Instant.now(), deadline);
        return left.isNegative() ? Duration.ZERO : left;
    }

    private static ModelStreamEvent finished(String subject, ChatResponse last) {
        if (last == null || last.getMetadata() == null) {
            return new ModelStreamEvent.Finished(null, null, null);
        }
        Usage usage = last.getMetadata().getUsage();
        Integer input = usage == null ? null : usage.getPromptTokens();
        Integer output = usage == null ? null : usage.getCompletionTokens();
        String model = last.getMetadata().getModel();
        log.info("AI {} streamed: model={} inputTokens={} outputTokens={}", subject, model, input, output);
        return new ModelStreamEvent.Finished(emptyToNull(model), positive(input), positive(output));
    }

    /** Providers report 0 when they didn't count; null says "unknown" more honestly. */
    private static Integer positive(Integer n) {
        return n == null || n <= 0 ? null : n;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String text(ChatResponse response) {
        return rawText(response).strip();
    }

    private static String rawText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private static void logUsage(String subject, ChatResponse response) {
        if (response.getMetadata() == null) {
            return;
        }
        Usage usage = response.getMetadata().getUsage();
        log.info("AI {} answered: model={} inputTokens={} outputTokens={}",
                subject, response.getMetadata().getModel(),
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens());
    }
}
