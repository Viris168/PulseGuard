package com.viris.PulseGuard.ai.chat;

import com.viris.PulseGuard.ai.AiAccess;
import com.viris.PulseGuard.ai.AiAccessRepository;
import com.viris.PulseGuard.ai.AiProperties;
import com.viris.PulseGuard.ai.AiQuotaPolicy;
import com.viris.PulseGuard.ai.AskAiSnapshot;
import com.viris.PulseGuard.ai.AskAiSnapshotLoader;
import com.viris.PulseGuard.ai.ModelCaller;
import com.viris.PulseGuard.ai.tools.MonitorTools;
import com.viris.PulseGuard.ai.tools.ToolGuard;
import com.viris.PulseGuard.ai.tools.ToolRun;
import com.viris.PulseGuard.ai.tools.ToolScope;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.billing.PlanLimits;
import com.viris.PulseGuard.common.exception.AiRuleException;
import com.viris.PulseGuard.common.exception.ChatNotFoundException;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final MonitorRepository monitors;
    private final UserRepository users;
    private final PlanLimits planLimits;
    private final ToolGuard toolGuard;
    private final ChatContextLoader contextLoader;
    /** The tools as the model sees them; stateless, so built once. Each message guards them anew. */
    private final List<ToolCallback> tools;

    public ChatService(AiConversationRepository conversations, AiMessageRepository messages,
                       AiAccessRepository accessRepository, AskAiSnapshotLoader snapshotLoader,
                       ChatMessageStore store, AiQuotaPolicy quota, ModelCaller modelCaller, AiProperties properties,
                       MonitorRepository monitors, UserRepository users, PlanLimits planLimits,
                       ToolGuard toolGuard, MonitorTools monitorTools, ChatContextLoader contextLoader) {
        this.conversations = conversations;
        this.messages = messages;
        this.accessRepository = accessRepository;
        this.snapshotLoader = snapshotLoader;
        this.store = store;
        this.quota = quota;
        this.modelCaller = modelCaller;
        this.properties = properties;
        this.monitors = monitors;
        this.users = users;
        this.planLimits = planLimits;
        this.toolGuard = toolGuard;
        this.contextLoader = contextLoader;
        this.tools = List.of(ToolCallbacks.from(monitorTools));
    }

    /**
     * Checks, counts and saves the question, then returns the answer ready to start. Nothing is
     * sent to the model until {@link ChatTurn#start} is called.
     */
    public ChatTurn send(Long userId, Long conversationId, String question, String timeZone) {
        AiConversation chat = conversations.findByIdAndUserId(conversationId, userId)
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
            ZoneId zone = AskAiSnapshotLoader.zone(timeZone);
            AskAiSnapshot snapshot = snapshotLoader.load(userId, access, zone);
            Long questionId = store.saveQuestion(userId, conversationId, question);
            String page = contextLoader.describe(chat, userId, access, zone, Instant.now()).orElse(null);
            Prompt prompt = ChatPrompt.build(question, snapshot, earlier, page);
            ToolRun run = toolGuard.start();
            return new ChatTurn(modelCaller.stream(prompt, "chat conversationId=" + conversationId, run.guard(tools),
                    scope(userId, access, zone).asToolContext()),
                    store, quota, userId, conversationId, questionId, run);
        } catch (RuntimeException e) {
            quota.release(userId);
            throw e;
        }
    }

    /**
     * What this message's tools may see, from the server's own records: the login, the monitors
     * the user shared, the plan's history. Nothing here comes from the model or the request body.
     */
    private ToolScope scope(Long userId, AiAccess access, ZoneId zone) {
        Set<Long> shared = monitors.findAllByUserId(userId).stream()
                .map(Monitor::getId)
                .filter(access::allows)
                .collect(Collectors.toSet());
        int historyDays = planLimits.retentionDays(users.findById(userId).orElseThrow().getPlan());
        return new ToolScope(userId, shared, zone, historyDays, Instant.now());
    }

    /** How long the browser's connection may stay open: the whole answer's limit plus a margin. */
    public Duration streamTimeout() {
        return properties.streamTimeout().plusSeconds(30);
    }
}
