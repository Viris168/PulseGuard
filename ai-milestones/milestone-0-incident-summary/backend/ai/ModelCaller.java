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

import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    private static String text(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text.strip();
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
