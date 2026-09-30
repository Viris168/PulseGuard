package com.viris.PulseGuard.ai.chat;

import com.viris.PulseGuard.ai.AskAiPrompt;
import com.viris.PulseGuard.ai.AskAiSnapshot;
import com.viris.PulseGuard.ai.PromptText;
import com.viris.PulseGuard.enumeration.MessageRole;
import com.viris.PulseGuard.enumeration.MessageStatus;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One chat turn's prompt. Pure, no I/O. The model remembers nothing between calls, so each turn
 * sends the conversation again:
 * <pre>
 *   system:    Ask AI's rules + how to use the conversation
 *   user:      an earlier question        ┐ the newest earlier turns that fit
 *   assistant: its answer                 ┘ HISTORY_BUDGET_CHARS, oldest first
 *   user:      &lt;data&gt;fresh snapshot&lt;/data&gt; + the new question
 * </pre>
 * The snapshot rides only on the newest message: answers use current data, and old turns don't
 * each carry a copy that would be paid for again on every turn.
 */
public final class ChatPrompt {

    /** About 1,500 tokens of history per turn, so a long chat doesn't cost more and more. */
    public static final int HISTORY_BUDGET_CHARS = 6000;

    static final String STOPPED_NOTE = "[stopped before finishing]";

    public static final String SYSTEM = AskAiPrompt.SYSTEM + """

            - This is a conversation. Use the earlier messages for context, such as what "it" or \
            "that" refers to, but take every fact from the newest <data>: earlier answers may be out \
            of date.""";

    private ChatPrompt() {
    }

    /**
     * @param earlier the conversation so far, oldest first, without the new question
     */
    public static Prompt build(String question, AskAiSnapshot snapshot, List<AiMessage> earlier) {
        return build(question, snapshot, earlier, null);
    }

    /**
     * @param page which page the chat was started from (ChatContextLoader), or null; placed with
     *             the data on the newest message, so it's current and never repeated per turn
     */
    public static Prompt build(String question, AskAiSnapshot snapshot, List<AiMessage> earlier, String page) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM));
        for (Turn turn : withinBudget(turns(earlier))) {
            messages.add(new UserMessage(turn.question()));
            messages.add(new AssistantMessage(turn.answer()));
        }
        String context = page == null ? "" : "\n\nPage: " + PromptText.noTags(page);
        messages.add(new UserMessage(AskAiPrompt.facts(snapshot) + context + "\n\nQuestion: " + PromptText.noTags(question)));
        return new Prompt(messages);
    }

    /** A question and the answer it got; tags already removed from both. */
    record Turn(String question, String answer) {
        int length() {
            return question.length() + answer.length();
        }
    }

    /**
     * Pairs each question with its answer, oldest first. A question whose answer failed, or that
     * never got one, is left out with it: the model would otherwise see a question it seemingly
     * ignored, and providers expect questions and answers to alternate.
     */
    static List<Turn> turns(List<AiMessage> earlier) {
        List<Turn> turns = new ArrayList<>();
        String question = null;
        for (AiMessage m : earlier) {
            if (m.getRole() == MessageRole.USER) {
                question = PromptText.noTags(m.getContent());
                continue;
            }
            String answer = PromptText.noTags(m.getContent());
            if (question != null && m.getStatus() != MessageStatus.FAILED && !answer.isEmpty()) {
                turns.add(new Turn(question, m.getStatus() == MessageStatus.PARTIAL
                        ? answer + "\n" + STOPPED_NOTE
                        : answer));
            }
            question = null;
        }
        return turns;
    }

    /**
     * The newest turns whose text fits the budget, back in oldest-first order. Stops at the first
     * turn that doesn't fit rather than skipping it, so the model never sees a gap in the middle.
     */
    static List<Turn> withinBudget(List<Turn> turns) {
        List<Turn> kept = new ArrayList<>();
        int used = 0;
        for (int i = turns.size() - 1; i >= 0; i--) {
            Turn turn = turns.get(i);
            if (used + turn.length() > HISTORY_BUDGET_CHARS) {
                break;
            }
            kept.add(turn);
            used += turn.length();
        }
        Collections.reverse(kept);
        return kept;
    }
}
