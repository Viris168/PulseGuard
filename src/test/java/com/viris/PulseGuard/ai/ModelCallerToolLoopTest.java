package com.viris.PulseGuard.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The tool loop in ModelCaller.stream (Spring AI 2.0 leaves it to us): a scripted model asks for
 * tools in one round and answers in the next; a fake tool records what it was called with.
 */
class ModelCallerToolLoopTest {

    private static final Prompt PROMPT = new Prompt(List.of(new UserMessage("Uptime of Health on 1 Sep?")));
    private static final String ARGS = "{\"monitor\":\"Health\",\"from\":\"2026-09-01\",\"to\":\"2026-09-01\"}";

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    private final FakeTool uptime = new FakeTool("get_uptime", "Health on 2026-09-01: 99.82% up");
    private final FakeTool incidents = new FakeTool("get_incidents", "No incidents");

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void runsTheToolTheModelAsksForThenStreamsTheAnswer() {
        ModelCaller caller = caller(round -> switch (round) {
            case 0 -> Flux.just(toolCalls(100, 10, new AssistantMessage.ToolCall("call-1", "function", "get_uptime", ARGS)));
            default -> Flux.just(text("Health was up "), last("99.82%.", 300, 25));
        });

        List<ModelStreamEvent> events = caller.stream(PROMPT, "test", List.of(uptime, incidents), Map.of("scope", "alice"))
                .collectList().block();

        assertThat(events).containsExactly(
                new ModelStreamEvent.Text("Health was up "),
                new ModelStreamEvent.Text("99.82%."),
                new ModelStreamEvent.Finished("configured-model", 400, 35)); // both rounds are billed
        assertThat(uptime.calls).containsExactly(ARGS + " scope=alice");
        assertThat(incidents.calls).isEmpty();
    }

    @Test
    void sendsTheToolResultBackInTheNextRound() {
        ModelCaller caller = caller(round -> round == 0
                ? Flux.just(toolCalls(0, 0, new AssistantMessage.ToolCall("call-1", "function", "get_uptime", ARGS)))
                : Flux.just(text("Done.")));

        caller.stream(PROMPT, "test", List.of(uptime), Map.of()).blockLast();

        assertThat(prompts).hasSize(2);
        List<MessageType> second = prompts.get(1).getInstructions().stream().map(m -> m.getMessageType()).toList();
        assertThat(second).containsExactly(MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL);
        ToolResponseMessage result = (ToolResponseMessage) prompts.get(1).getInstructions().getLast();
        assertThat(result.getResponses().getFirst().responseData()).isEqualTo("Health on 2026-09-01: 99.82% up");
    }

    @Test
    void keepsTheConfiguredModelSettingsWhenAddingTools() {
        // Options sent with a request replace the model's defaults; tools alone would switch model.
        ModelCaller caller = caller(round -> Flux.just(text("Hi")));

        caller.stream(PROMPT, "test", List.of(uptime), Map.of("scope", "alice")).blockLast();

        ChatOptions sent = prompts.getFirst().getOptions();
        assertThat(sent).isInstanceOf(ToolCallingChatOptions.class);
        assertThat(sent.getModel()).isEqualTo("configured-model");
        assertThat(sent.getMaxTokens()).isEqualTo(1024);
        assertThat(((ToolCallingChatOptions) sent).getToolCallbacks()).containsExactly(uptime);
        assertThat(((ToolCallingChatOptions) sent).getToolContext()).containsEntry("scope", "alice");
    }

    @Test
    void runsEveryToolAskedForInOneReply() {
        ModelCaller caller = caller(round -> round == 0
                ? Flux.just(toolCalls(0, 0,
                        new AssistantMessage.ToolCall("call-1", "function", "get_uptime", ARGS),
                        new AssistantMessage.ToolCall("call-2", "function", "get_incidents", "{}")))
                : Flux.just(text("Both checked.")));

        caller.stream(PROMPT, "test", List.of(uptime, incidents), Map.of()).blockLast();

        assertThat(uptime.calls).hasSize(1);
        assertThat(incidents.calls).hasSize(1);
    }

    @Test
    void gathersToolCallsSplitAcrossStreamedPieces() {
        ModelCaller caller = caller(round -> round == 0
                ? Flux.just(toolCalls(0, 0, new AssistantMessage.ToolCall("call-1", "function", "get_uptime", ARGS)),
                        toolCalls(0, 0, new AssistantMessage.ToolCall("call-2", "function", "get_incidents", "{}")))
                : Flux.just(text("Both checked.")));

        caller.stream(PROMPT, "test", List.of(uptime, incidents), Map.of()).blockLast();

        assertThat(uptime.calls).hasSize(1);
        assertThat(incidents.calls).hasSize(1);
    }

    @Test
    void stopsAskingTheModelAfterTheRoundLimit() {
        // A confused model that asks for a tool forever: max-tool-calls (5) + 1 rounds, then stop.
        ModelCaller caller = caller(round ->
                Flux.just(toolCalls(0, 0, new AssistantMessage.ToolCall("call-" + round, "function", "get_uptime", ARGS))));

        List<ModelStreamEvent> events = caller.stream(PROMPT, "test", List.of(uptime), Map.of()).collectList().block();

        assertThat(prompts).hasSize(6);
        assertThat(events).containsExactly(new ModelStreamEvent.Finished("configured-model", null, null));
    }

    @Test
    void withoutToolsThePromptIsSentAsItIs() {
        ModelCaller caller = caller(round -> Flux.just(text("Hi")));

        caller.stream(PROMPT, "test").blockLast();

        assertThat(prompts).containsExactly(PROMPT);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    /** A model that answers round n with {@code script.apply(n)} and records every prompt. */
    private ModelCaller caller(Function<Integer, Flux<ChatResponse>> script) {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException("streaming only");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                int round = prompts.size();
                prompts.add(prompt);
                return script.apply(round);
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return ToolCallingChatOptions.builder().model("configured-model").maxTokens(1024).build();
            }
        };
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        return new ModelCaller(provider, new AiProperties(Duration.ofMinutes(10), Duration.ofSeconds(2),
                Duration.ofSeconds(10), 500, 5, Duration.ofSeconds(1)), executor);
    }

    private static ChatResponse text(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static ChatResponse last(String text, int in, int out) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().model("configured-model").usage(new DefaultUsage(in, out)).build());
    }

    private static ChatResponse toolCalls(int in, int out, AssistantMessage.ToolCall... calls) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(calls)).build())),
                ChatResponseMetadata.builder().model("configured-model").usage(new DefaultUsage(in, out)).build());
    }

    /** Returns a fixed result and records "arguments scope=…" for each call. */
    static class FakeTool implements ToolCallback {
        final List<String> calls = new CopyOnWriteArrayList<>();
        private final ToolDefinition definition;
        private final String result;

        FakeTool(String name, String result) {
            this.definition = ToolDefinition.builder().name(name).description("test").inputSchema("{\"type\":\"object\"}").build();
            this.result = result;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String input) {
            return call(input, null);
        }

        @Override
        public String call(String input, ToolContext context) {
            Object scope = context == null ? null : context.getContext().get("scope");
            calls.add(input + (scope == null ? "" : " scope=" + scope));
            return result;
        }
    }
}
