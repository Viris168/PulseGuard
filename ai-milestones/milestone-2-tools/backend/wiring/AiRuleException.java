package com.viris.PulseGuard.common.exception;

import org.springframework.http.HttpStatus;

/** An Ask AI request that can't be answered right now. The message is safe to show the user. */
public class AiRuleException extends RuntimeException {

    private final HttpStatus status;

    private AiRuleException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public static AiRuleException disabled() {
        return new AiRuleException(HttpStatus.FORBIDDEN, "Turn on Ask AI first.");
    }

    public static AiRuleException noMonitorSelected() {
        return new AiRuleException(HttpStatus.BAD_REQUEST, "Select at least one monitor.");
    }

    public static AiRuleException quotaReached(String message) {
        return new AiRuleException(HttpStatus.TOO_MANY_REQUESTS, message);
    }

    public static AiRuleException notConfigured() {
        return new AiRuleException(HttpStatus.SERVICE_UNAVAILABLE, "Ask AI isn't available on this server.");
    }

    public static AiRuleException conversationFull(int max) {
        return new AiRuleException(HttpStatus.CONFLICT,
                "This chat has reached " + max + " messages. Start a new chat to keep going.");
    }

    public static AiRuleException contextNotBoth() {
        return new AiRuleException(HttpStatus.BAD_REQUEST, "Start a chat about a monitor or an incident, not both.");
    }

    public static AiRuleException invalidRating() {
        return new AiRuleException(HttpStatus.BAD_REQUEST, "Rate an answer with thumbs up (1) or down (-1).");
    }

    public static AiRuleException onlyAnswersCanBeRated() {
        return new AiRuleException(HttpStatus.BAD_REQUEST, "Only Ask AI's answers can be rated.");
    }

    public HttpStatus getStatus() {
        return status;
    }
}
