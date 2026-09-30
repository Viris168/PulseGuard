package com.viris.PulseGuard.ai.tools;

import com.viris.PulseGuard.ai.PromptText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Wraps one tool with the rules every tool call follows (AI_MILESTONE_2.md, Part 3):
 * <ul>
 *   <li><b>cap</b>: past {@code pulseguard.ai.max-tool-calls} the tool isn't run and the model is
 *       told to answer with what it has;</li>
 *   <li><b>time limit</b>: past {@code pulseguard.ai.tool-timeout} the model is told the lookup
 *       took too long;</li>
 *   <li><b>never throws</b>: a failing tool becomes a short safe message; the exception's text,
 *       which could hold anything, is neither returned nor logged;</li>
 *   <li><b>bounded, fenced result</b>: at most {@value #MAX_RESULT_CHARS} characters, angle
 *       brackets removed, inside {@code <tool_result>}, because it can hold error text from
 *       monitored servers;</li>
 *   <li><b>a JSON object</b>: {@code {"result": "<tool_result>…</tool_result>"}}. Gemini's client
 *       parses every tool result into the object its {@code functionResponse} needs, and fails on
 *       plain text; Anthropic takes any string, so one shape works for both;</li>
 *   <li><b>recorded</b>: every call, refused ones included, goes to the {@link ToolRun}.</li>
 * </ul>
 * The model sees the same name, description and parameters as the wrapped tool.
 */
final class GuardedToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(GuardedToolCallback.class);

    static final int MAX_RESULT_CHARS = 2000;
    static final int PREVIEW_CHARS = 1000;
    static final int MAX_ARGUMENT_CHARS = 500;

    static final String LIMIT_REACHED =
            "Tool limit reached for this question. Answer with what you have, and say what you couldn't check.";
    static final String TIMED_OUT = "This lookup took too long. Answer without it, and say it couldn't be checked.";
    static final String FAILED = "This lookup failed. Answer without it, and say it couldn't be checked.";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ToolCallback tool;
    private final ToolRun run;

    GuardedToolCallback(ToolCallback tool, ToolRun run) {
        this.tool = tool;
        this.run = run;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return tool.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return tool.getToolMetadata();
    }

    @Override
    public String call(String arguments) {
        return call(arguments, null);
    }

    @Override
    public String call(String arguments, ToolContext context) {
        String name = getToolDefinition().name();
        if (!run.tryStart()) {
            finish(name, arguments, LIMIT_REACHED, false, 0);
            return forModel(LIMIT_REACHED);
        }
        long started = System.nanoTime();
        Future<String> call = run.executor().submit(() -> tool.call(arguments, context));
        String result;
        boolean ok = false;
        try {
            result = bounded(call.get(run.timeout().toMillis(), TimeUnit.MILLISECONDS));
            ok = true;
        } catch (TimeoutException e) {
            call.cancel(true);
            result = TIMED_OUT;
        } catch (ExecutionException e) {
            log.warn("Tool {} failed: {}", name, e.getCause().getClass().getSimpleName());
            result = FAILED;
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            result = FAILED;
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        finish(name, arguments, result, ok, ms);
        return forModel(result);
    }

    private void finish(String name, String arguments, String result, boolean ok, long ms) {
        log.info("Tool {} ok={} in {} ms", name, ok, ms);
        run.record(new ToolCallRecord(name, cut(arguments, MAX_ARGUMENT_CHARS), cut(result, PREVIEW_CHARS), ok, ms));
    }

    private static String bounded(String result) {
        String text = result == null ? "" : result;
        return text.length() <= MAX_RESULT_CHARS
                ? text
                : text.substring(0, MAX_RESULT_CHARS) + "\n…(result cut at " + MAX_RESULT_CHARS + " characters)";
    }

    /** What the model is sent: the fenced text as the one field of a JSON object. */
    private static String forModel(String result) {
        return JSON.writeValueAsString(Map.of("result", fence(result)));
    }

    /** Data for the model to read, never instructions: no tag in it can end the fence early. */
    private static String fence(String result) {
        return "<tool_result>\n" + PromptText.noTags(result) + "\n</tool_result>";
    }

    private static String cut(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
