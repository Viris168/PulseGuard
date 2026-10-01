package com.viris.PulseGuard.ai.chat;

import com.viris.PulseGuard.ai.chat.dto.ConversationResponse;
import com.viris.PulseGuard.ai.chat.dto.MessageResponse;
import com.viris.PulseGuard.ai.AiAccess;
import com.viris.PulseGuard.ai.AiAccessRepository;
import com.viris.PulseGuard.ai.chat.dto.CreateConversationRequest;
import com.viris.PulseGuard.ai.tools.ToolCallRecord;
import com.viris.PulseGuard.ai.tools.ToolLabels;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.common.exception.IncidentNotFoundException;
import com.viris.PulseGuard.common.exception.MonitorNotFoundException;
import com.viris.PulseGuard.incident.Incident;
import com.viris.PulseGuard.incident.IncidentRepository;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
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
    private final AiToolCallRepository toolCalls;
    private final UserRepository users;
    private final AiAccessRepository accessRepository;
    private final MonitorRepository monitors;
    private final IncidentRepository incidents;

    public ConversationService(AiConversationRepository conversations, AiMessageRepository messages,
                               AiMessageFeedbackRepository feedback, AiToolCallRepository toolCalls,
                               UserRepository users, AiAccessRepository accessRepository,
                               MonitorRepository monitors, IncidentRepository incidents) {
        this.conversations = conversations;
        this.messages = messages;
        this.feedback = feedback;
        this.toolCalls = toolCalls;
        this.users = users;
        this.accessRepository = accessRepository;
        this.monitors = monitors;
        this.incidents = incidents;
    }

    @Transactional(readOnly = true)
    public List<ConversationResponse> list(Long userId) {
        return conversations.findAllByUserIdOrderByUpdatedAtDesc(userId, PageRequest.of(0, MAX_LISTED)).stream()
                .map(ConversationResponse::from)
                .toList();
    }

    /**
     * An empty chat; its first message gives it a real title. Started from a monitor's or an
     * incident's page, it remembers which, once checked: the monitor must be the caller's and shared
     * with Ask AI, else it's a 404 exactly like one that doesn't exist.
     */
    @Transactional
    public ConversationResponse create(Long userId, CreateConversationRequest request) {
        AiConversation conversation = new AiConversation(users.getReferenceById(userId), NEW_CHAT_TITLE);
        if (request.monitorId() != null && request.incidentId() != null) {
            throw AiRuleException.contextNotBoth();
        }
        if (request.monitorId() != null || request.incidentId() != null) {
            AiAccess access = accessRepository.findById(userId).filter(AiAccess::isEnabled)
                    .orElseThrow(AiRuleException::disabled);
            if (request.incidentId() != null) {
                Incident incident = incidents.findByIdAndMonitorUserId(request.incidentId(), userId)
                        .filter(i -> access.allows(i.getMonitor().getId()))
                        .orElseThrow(() -> new IncidentNotFoundException(request.incidentId()));
                conversation.setContextIncidentId(incident.getId());
                conversation.setContextMonitorId(incident.getMonitor().getId());
            } else {
                Monitor monitor = monitors.findByIdAndUserId(request.monitorId(), userId)
                        .filter(m -> access.allows(m.getId()))
                        .orElseThrow(() -> new MonitorNotFoundException(request.monitorId()));
                conversation.setContextMonitorId(monitor.getId());
            }
        }
        return ConversationResponse.from(conversations.save(conversation));
    }

    /** Oldest first, each answer with the caller's rating and what it looked up. */
    @Transactional(readOnly = true)
    public List<MessageResponse> messages(Long userId, Long conversationId) {
        owned(userId, conversationId);
        List<AiMessage> list = messages.findAllByConversationIdAndConversationUserIdOrderByIdAsc(conversationId, userId);
        List<Long> answerIds = list.stream().filter(m -> m.getRole() == MessageRole.ASSISTANT)
                .map(AiMessage::getId).toList();
        Map<Long, Short> ratings = feedback.findAllById(answerIds).stream()
                .collect(Collectors.toMap(AiMessageFeedback::getMessageId, AiMessageFeedback::getRating));
        Map<Long, List<ToolCallRecord>> calls = toolCalls.findAllByMessageIdInOrderByIdAsc(answerIds).stream()
                .collect(Collectors.groupingBy(AiToolCall::getMessageId,
                        Collectors.mapping(AiToolCall::toRecord, Collectors.toList())));
        return list.stream()
                .map(m -> {
                    List<ToolCallRecord> made = calls.getOrDefault(m.getId(), List.of());
                    return MessageResponse.from(m, ratings.get(m.getId()),
                            made.stream().map(ToolLabels::label).toList(),
                            // Each section once, in the order the searches found them.
                            made.stream().flatMap(c -> c.sources().stream()).distinct().toList());
                })
                .toList();
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
