package com.viris.PulseGuard.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
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
    /** Runs the tools a reply asks for and builds the next round's messages. */
    private final ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();

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
        return stream(prompt, subject, List.of(), Map.of());
    }

    /**
     * {@link #stream(Prompt, String)} with tools the model may call (AI_MILESTONE_2.md). Spring AI
     * 2.0 leaves the tool loop to the caller, so this runs it: each round streams the model's reply;
     * if the reply asks for tools, they run (on a worker thread, as they read the database) and the
     * next round sends their results back. Text from every round streams as it arrives. At most
     * {@code pulseguard.ai.max-tool-calls} + 1 rounds: the tools themselves refuse calls past the cap.
     *
     * @param tools       already guarded (ToolRun): capped, timed, recorded
     * @param toolContext what the tools run under; the model never sees it
     */
    public Flux<ModelStreamEvent> stream(Prompt prompt, String subject, List<ToolCallback> tools,
                                         Map<String, Object> toolContext) {
        return Flux.defer(() -> {
            ChatModel model = chatModel.getIfAvailable();
            if (model == null) {
                return Flux.error(ModelStreamException.notConfigured());
            }
            Instant deadline = Instant.now().plus(properties.streamTimeout());
            Prompt first = tools.isEmpty() ? prompt : withTools(prompt, model, tools, toolContext);
            // One entry per round: its last response carries that round's token usage.
            List<ChatResponse> roundTotals = new CopyOnWriteArrayList<>();
            return round(model, first, 0, tools.isEmpty() ? 0 : properties.maxToolCalls() + 1, deadline, roundTotals)
                    .concatWith(Mono.fromSupplier(() -> finished(subject, roundTotals)))
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

    /** One model reply, then, if it asked for tools and rounds are left, the next round. */
    private Flux<ModelStreamEvent> round(ChatModel model, Prompt prompt, int round, int maxRounds, Instant deadline,
                                         List<ChatResponse> roundTotals) {
        List<ChatResponse> toolCalls = new CopyOnWriteArrayList<>();
        AtomicReference<ChatResponse> last = new AtomicReference<>();
        Flux<ModelStreamEvent> text = model.stream(prompt)
                // First piece within the timeout; every later piece before the overall deadline.
                .timeout(Mono.delay(min(properties.timeout(), untilDeadline(deadline))),
                        piece -> Mono.delay(untilDeadline(deadline)))
                .doOnNext(response -> {
                    if (response == null) {
                        return;
                    }
                    if (response.getMetadata() != null) {
                        last.set(response);
                    }
                    if (response.hasToolCalls()) {
                        toolCalls.add(response);
                    }
                })
                // Unstripped: the spaces between pieces are part of the answer.
                .map(ModelCaller::rawText)
                .filter(t -> !t.isEmpty())
                .map(ModelStreamEvent.Text::new);
        return text.concatWith(Flux.defer(() -> {
            if (last.get() != null) {
                roundTotals.add(last.get());
            }
            if (toolCalls.isEmpty() || round + 1 >= maxRounds) {
                return Flux.<ModelStreamEvent>empty();
            }
            ChatResponse asked = merged(toolCalls);
            return Mono.fromCallable(() -> toolCallingManager.executeToolCalls(prompt, asked))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(result -> round(model, new Prompt(result.conversationHistory(), prompt.getOptions()),
                            round + 1, maxRounds, deadline, roundTotals));
        }));
    }

    /**
     * The request options: the model's configured defaults (model name, token cap, thinking…)
     * plus the tools. Options sent with a request replace the defaults rather than add to them,
     * so tools alone would silently switch to Spring AI's built-in default model.
     */
    static Prompt withTools(Prompt prompt, ChatModel model, List<ToolCallback> tools, Map<String, Object> toolContext) {
        ChatOptions defaults = model.getDefaultOptions();
        ToolCallingChatOptions options = defaults instanceof ToolCallingChatOptions configured
                ? configured.mutate().toolCallbacks(tools).toolContext(toolContext).build()
                : ToolCallingChatOptions.builder().toolCallbacks(tools).toolContext(toolContext).build();
        return new Prompt(prompt.getInstructions(), options);
    }

    /** A reply's tool calls can arrive across several streamed pieces: gather them into one. */
    private static ChatResponse merged(List<ChatResponse> pieces) {
        if (pieces.size() == 1) {
            return pieces.getFirst();
        }
        List<AssistantMessage.ToolCall> calls = pieces.stream()
                .flatMap(r -> r.getResult().getOutput().getToolCalls().stream())
                .toList();
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(calls).build())));
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static Duration untilDeadline(Instant deadline) {
        Duration left = Duration.between(Instant.now(), deadline);
        return left.isNegative() ? Duration.ZERO : left;
    }

    /** Tokens summed over every round: each tool round is billed. Model name from the last. */
    private static ModelStreamEvent finished(String subject, List<ChatResponse> rounds) {
        Integer input = null;
        Integer output = null;
        String model = null;
        for (ChatResponse r : rounds) {
            if (r.getMetadata() == null) {
                continue;
            }
            Usage usage = r.getMetadata().getUsage();
            if (usage != null) {
                input = sum(input, usage.getPromptTokens());
                output = sum(output, usage.getCompletionTokens());
            }
            if (r.getMetadata().getModel() != null && !r.getMetadata().getModel().isBlank()) {
                model = r.getMetadata().getModel();
            }
        }
        log.info("AI {} streamed: model={} rounds={} inputTokens={} outputTokens={}", subject, model, rounds.size(),
                input, output);
        return new ModelStreamEvent.Finished(emptyToNull(model), positive(input), positive(output));
    }

    private static Integer sum(Integer total, Integer add) {
        if (add == null || add <= 0) {
            return total;
        }
        return total == null ? add : total + add;
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
