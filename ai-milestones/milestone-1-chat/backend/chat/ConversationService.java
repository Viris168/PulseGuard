package com.viris.PulseGuard.ai.chat;

import com.viris.PulseGuard.ai.chat.dto.ConversationResponse;
import com.viris.PulseGuard.ai.chat.dto.MessageResponse;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.common.exception.AiRuleException;
import com.viris.PulseGuard.common.exception.ChatNotFoundException;
import com.viris.PulseGuard.enumeration.MessageRole;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Ask AI conversations: listing, reading, renaming, deleting and rating. Sending a message (the
 * streaming part) is ChatService. Every method takes the caller's user id and looks rows up with
 * it, so another user's conversation is a 404 exactly like a missing one.
 */
@Service
public class ConversationService {

    /** The panel's list; older chats stay until retention deletes them. */
    static final int MAX_LISTED = 50;
    static final String NEW_CHAT_TITLE = "New chat";

    private final AiConversationRepository conversations;
    private final AiMessageRepository messages;
    private final AiMessageFeedbackRepository feedback;
    private final UserRepository users;

    public ConversationService(AiConversationRepository conversations, AiMessageRepository messages,
                               AiMessageFeedbackRepository feedback, UserRepository users) {
        this.conversations = conversations;
        this.messages = messages;
        this.feedback = feedback;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<ConversationResponse> list(Long userId) {
        return conversations.findAllByUserIdOrderByUpdatedAtDesc(userId, PageRequest.of(0, MAX_LISTED)).stream()
                .map(ConversationResponse::from)
                .toList();
    }

    /** An empty chat; its first message gives it a real title. */
    @Transactional
    public ConversationResponse create(Long userId) {
        AiConversation conversation = new AiConversation(users.getReferenceById(userId), NEW_CHAT_TITLE);
        return ConversationResponse.from(conversations.save(conversation));
    }

    /** Oldest first, each answer with the caller's rating if they gave one. */
    @Transactional(readOnly = true)
    public List<MessageResponse> messages(Long userId, Long conversationId) {
        owned(userId, conversationId);
        List<AiMessage> list = messages.findAllByConversationIdAndConversationUserIdOrderByIdAsc(conversationId, userId);
        List<Long> answerIds = list.stream().filter(m -> m.getRole() == MessageRole.ASSISTANT)
                .map(AiMessage::getId).toList();
        Map<Long, Short> ratings = feedback.findAllById(answerIds).stream()
                .collect(Collectors.toMap(AiMessageFeedback::getMessageId, AiMessageFeedback::getRating));
        return list.stream().map(m -> MessageResponse.from(m, ratings.get(m.getId()))).toList();
    }

    @Transactional
    public ConversationResponse rename(Long userId, Long conversationId, String title) {
        AiConversation conversation = owned(userId, conversationId);
        conversation.setTitle(title.strip());
        return ConversationResponse.from(conversation);
    }

    /** Its messages and their ratings go with it (ON DELETE CASCADE). */
    @Transactional
    public void delete(Long userId, Long conversationId) {
        conversations.delete(owned(userId, conversationId));
    }

    /** Thumbs up or down on one of the caller's answers; rating again replaces the earlier one. */
    @Transactional
    public void rate(Long userId, Long messageId, short rating) {
        if (rating != AiMessageFeedback.THUMBS_UP && rating != AiMessageFeedback.THUMBS_DOWN) {
            throw AiRuleException.invalidRating();
        }
        AiMessage message = messages.findByIdAndConversationUserId(messageId, userId)
                .orElseThrow(() -> ChatNotFoundException.message(messageId));
        if (message.getRole() != MessageRole.ASSISTANT) {
            throw AiRuleException.onlyAnswersCanBeRated();
        }
        AiMessageFeedback row = feedback.findById(messageId).orElseGet(() -> new AiMessageFeedback(messageId, rating));
        row.setRating(rating);
        row.setCreatedAt(Instant.now());
        feedback.save(row);
    }

    private AiConversation owned(Long userId, Long conversationId) {
        return conversations.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> ChatNotFoundException.conversation(conversationId));
    }
}
