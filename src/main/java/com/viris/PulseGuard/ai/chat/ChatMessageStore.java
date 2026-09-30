package com.viris.PulseGuard.ai.chat;

import com.viris.PulseGuard.ai.ModelStreamEvent;
import com.viris.PulseGuard.common.exception.ChatNotFoundException;
import com.viris.PulseGuard.enumeration.MessageRole;
import com.viris.PulseGuard.enumeration.MessageStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The chat's short database writes and reads, each in its own transaction, so no connection is
 * held while the model writes. Called from the request thread and from the streaming threads.
 */
@Component
public class ChatMessageStore {

    /** Read for the prompt's history; ChatPrompt then trims to its character budget. */
    static final int HISTORY_MESSAGES = 40;
    static final int TITLE_LENGTH = 60;

    private final AiConversationRepository conversations;
    private final AiMessageRepository messages;

    public ChatMessageStore(AiConversationRepository conversations, AiMessageRepository messages) {
        this.conversations = conversations;
        this.messages = messages;
    }

    /** The conversation so far, oldest first, newest {@value #HISTORY_MESSAGES} at most. */
    @Transactional(readOnly = true)
    public List<AiMessage> history(Long userId, Long conversationId) {
        List<AiMessage> newestFirst = new ArrayList<>(messages.findAllByConversationIdAndConversationUserIdOrderByIdDesc(
                conversationId, userId, PageRequest.of(0, HISTORY_MESSAGES)));
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    /** Saves the question; the chat's first question becomes its title. */
    @Transactional
    public Long saveQuestion(Long userId, Long conversationId, String question) {
        AiConversation conversation = conversations.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> ChatNotFoundException.conversation(conversationId));
        if (messages.countByConversationId(conversationId) == 0
                && ConversationService.NEW_CHAT_TITLE.equals(conversation.getTitle())) {
            conversation.setTitle(title(question));
        }
        conversation.setUpdatedAt(Instant.now());
        return messages.save(new AiMessage(conversation, MessageRole.USER, question.strip(), MessageStatus.COMPLETE))
                .getId();
    }

    /** Saves the answer as it ended, with what the provider reported about it. */
    @Transactional
    public Long saveAnswer(Long conversationId, String text, MessageStatus status, ModelStreamEvent.Finished usage) {
        AiConversation conversation = conversations.getReferenceById(conversationId);
        conversation.setUpdatedAt(Instant.now());
        AiMessage answer = new AiMessage(conversation, MessageRole.ASSISTANT, text, status);
        if (usage != null) {
            answer.setModel(usage.model());
            answer.setInputTokens(usage.inputTokens());
            answer.setOutputTokens(usage.outputTokens());
        }
        return messages.save(answer).getId();
    }

    static String title(String question) {
        String line = question.strip().replaceAll("\\s+", " ");
        return line.length() <= TITLE_LENGTH ? line : line.substring(0, TITLE_LENGTH - 1).strip() + "…";
    }
}
