package com.viris.PulseGuard.ai.chat;

import com.viris.PulseGuard.ai.chat.dto.ConversationResponse;
import com.viris.PulseGuard.ai.chat.dto.FeedbackRequest;
import com.viris.PulseGuard.ai.chat.dto.MessageResponse;
import com.viris.PulseGuard.ai.chat.dto.RenameConversationRequest;
import com.viris.PulseGuard.ai.chat.dto.SendMessageRequest;
import com.viris.PulseGuard.auth.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * Ask AI conversations. Session only, like the rest of /api/ai/** (SecurityConfig). The user id
 * always comes from the login, never from the request.
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class ChatController {

    private final ConversationService conversationService;
    private final ChatService chatService;

    @GetMapping("/conversations")
    public List<ConversationResponse> list(@AuthenticationPrincipal UserPrincipal principal) {
        return conversationService.list(principal.getUserId());
    }

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationResponse create(@AuthenticationPrincipal UserPrincipal principal) {
        return conversationService.create(principal.getUserId());
    }

    @GetMapping("/conversations/{id}/messages")
    public List<MessageResponse> messages(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        return conversationService.messages(principal.getUserId(), id);
    }

    /**
     * Sends a question; the response is the answer as a Server-Sent Events stream: {@code delta}
     * pieces, then {@code done} or {@code error}. Refusals (not yours, Ask AI off, limit reached)
     * are thrown before the stream opens, so they come back as ordinary JSON errors.
     */
    @PostMapping("/conversations/{id}/messages")
    public ResponseEntity<SseEmitter> send(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id,
                                           @Valid @RequestBody SendMessageRequest request) {
        ChatTurn turn = chatService.send(principal.getUserId(), id, request.question(), request.timeZone());
        SseEmitter emitter = new SseEmitter(chatService.streamTimeout().toMillis());
        // Stop, a closed tab, or the connection timing out all end the answer the same way.
        emitter.onCompletion(turn::stop);
        emitter.onTimeout(turn::stop);
        emitter.onError(e -> turn.stop());
        turn.start(new SseChatEvents(emitter));
        // Asks proxies (nginx and alike) to pass each event on at once instead of buffering them.
        return ResponseEntity.ok().header("X-Accel-Buffering", "no").body(emitter);
    }

    @PatchMapping("/conversations/{id}")
    public ConversationResponse rename(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id,
                                       @Valid @RequestBody RenameConversationRequest request) {
        return conversationService.rename(principal.getUserId(), id, request.title());
    }

    @DeleteMapping("/conversations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        conversationService.delete(principal.getUserId(), id);
    }

    @PutMapping("/messages/{id}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rate(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id,
                     @Valid @RequestBody FeedbackRequest request) {
        conversationService.rate(principal.getUserId(), id, request.rating());
    }
}
