package com.viris.PulseGuard.common.exception;

/**
 * An Ask AI conversation or message that doesn't exist. Also thrown for another user's, so ids
 * cannot be probed: the answer is the same 404 either way.
 */
public class ChatNotFoundException extends RuntimeException {

    private ChatNotFoundException(String message) {
        super(message);
    }

    public static ChatNotFoundException conversation(Long id) {
        return new ChatNotFoundException("Conversation not found: " + id);
    }

    public static ChatNotFoundException message(Long id) {
        return new ChatNotFoundException("Message not found: " + id);
    }
}
