package com.viris.PulseGuard.ai.chat;

import com.viris.PulseGuard.ai.AiAccess;
import com.viris.PulseGuard.ai.AiAccessRepository;
import com.viris.PulseGuard.ai.AiProperties;
import com.viris.PulseGuard.ai.AiQuotaPolicy;
import com.viris.PulseGuard.ai.AskAiSnapshot;
import com.viris.PulseGuard.ai.AskAiSnapshotLoader;
import com.viris.PulseGuard.ai.ModelCaller;
import com.viris.PulseGuard.common.exception.AiRuleException;
import com.viris.PulseGuard.common.exception.ChatNotFoundException;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Sending a chat message. Every check runs before the stream opens, so a refusal reaches the
 * browser as an ordinary JSON error (404, 403, 409, 429, 503) rather than halfway down a stream.
 *
 * <p>Not {@code @Transactional}: the answer can take a minute to write. Each database step is its
 * own short transaction in {@link ChatMessageStore}.
 */
@Service
public class ChatService {

    /** Long chats cost more per turn and drift off topic; past this, start a new one. */
    public static final int MAX_MESSAGES = 50;

    private final AiConversationRepository conversations;
    private final AiMessageRepository messages;
    private final AiAccessRepository accessRepository;
    private final AskAiSnapshotLoader snapshotLoader;
    private final ChatMessageStore store;
    private final AiQuotaPolicy quota;
    private final ModelCaller modelCaller;
    private final AiProperties properties;

    public ChatService(AiConversationRepository conversations, AiMessageRepository messages,
                       AiAccessRepository accessRepository, AskAiSnapshotLoader snapshotLoader,
                       ChatMessageStore store, AiQuotaPolicy quota, ModelCaller modelCaller, AiProperties properties) {
        this.conversations = conversations;
        this.messages = messages;
        this.accessRepository = accessRepository;
        this.snapshotLoader = snapshotLoader;
        this.store = store;
        this.quota = quota;
        this.modelCaller = modelCaller;
        this.properties = properties;
    }

    /**
     * Checks, counts and saves the question, then returns the answer ready to start. Nothing is
     * sent to the model until {@link ChatTurn#start} is called.
     */
    public ChatTurn send(Long userId, Long conversationId, String question, String timeZone) {
        conversations.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> ChatNotFoundException.conversation(conversationId));
        AiAccess access = accessRepository.findById(userId).filter(AiAccess::isEnabled)
                .orElseThrow(AiRuleException::disabled);
        if (!modelCaller.isAvailable()) {
            throw AiRuleException.notConfigured();
        }
        if (messages.countByConversationId(conversationId) >= MAX_MESSAGES) {
            throw AiRuleException.conversationFull(MAX_MESSAGES);
        }
        quota.acquire(userId);

        try {
            // History first: it must not include the question saved just below.
            List<AiMessage> earlier = store.history(userId, conversationId);
            AskAiSnapshot snapshot = snapshotLoader.load(userId, access, AskAiSnapshotLoader.zone(timeZone));
            Long questionId = store.saveQuestion(userId, conversationId, question);
            Prompt prompt = ChatPrompt.build(question, snapshot, earlier);
            return new ChatTurn(modelCaller.stream(prompt, "chat conversationId=" + conversationId),
                    store, quota, userId, conversationId, questionId);
        } catch (RuntimeException e) {
            quota.release(userId);
            throw e;
        }
    }

    /** How long the browser's connection may stay open: the whole answer's limit plus a margin. */
    public Duration streamTimeout() {
        return properties.streamTimeout().plusSeconds(30);
    }
}
