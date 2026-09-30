package com.viris.PulseGuard.ai.tools;

import org.springframework.ai.tool.execution.ToolCallResultConverter;

import java.lang.reflect.Type;

/**
 * Hands a tool's text to the model as it is. Spring AI's default converter turns every result into
 * JSON, so a text answer would arrive quoted, with its line breaks as literal {@code \n}: harder
 * for the model to read, and our {@code <tool_result>} fence would wrap JSON instead of the text.
 */
public class PlainTextResult implements ToolCallResultConverter {

    @Override
    public String convert(Object result, Type returnType) {
        return result == null ? "" : result.toString();
    }
}
