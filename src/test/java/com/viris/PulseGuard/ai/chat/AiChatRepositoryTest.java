package com.viris.PulseGuard.ai.chat;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.common.AbstractRepositoryTest;
import com.viris.PulseGuard.enumeration.MessageRole;
import com.viris.PulseGuard.enumeration.MessageStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The chat tables against real Postgres: tenant scoping, ordering, cascades and constraints. */
class AiChatRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    AiConversationRepository conversations;
    @Autowired
    AiMessageRepository messages;
    @Autowired
    AiMessageFeedbackRepository feedback;

    @Test
    void findsAConversationOnlyForItsOwner() {
        User alice = newUser("alice@example.com");
        User bob = newUser("bob@example.com");
        AiConversation chat = conversation(alice, "Why is Health down?", Instant.now());

        assertThat(conversations.findByIdAndUserId(chat.getId(), alice.getId())).isPresent();
        assertThat(conversations.findByIdAndUserId(chat.getId(), bob.getId())).isEmpty();
        assertThat(conversations.findAllByUserIdOrderByUpdatedAtDesc(bob.getId(), PageRequest.of(0, 50))).isEmpty();
    }

    @Test
    void listsConversationsMostRecentlyUsedFirst() {
        User alice = newUser("alice@example.com");
        Instant now = Instant.now();
        conversation(alice, "Old", now.minusSeconds(3600));
        conversation(alice, "Newest", now);
        conversation(alice, "Middle", now.minusSeconds(60));

        List<AiConversation> list = conversations.findAllByUserIdOrderByUpdatedAtDesc(alice.getId(), PageRequest.of(0, 2));

        assertThat(list).extracting(AiConversation::getTitle).containsExactly("Newest", "Middle");
    }

    @Test
    void listsMessagesOldestFirstAndOnlyForTheOwner() {
        User alice = newUser("alice@example.com");
        User bob = newUser("bob@example.com");
        AiConversation chat = conversation(alice, "Chat", Instant.now());
        message(chat, MessageRole.USER, "Is anything down?");
        message(chat, MessageRole.ASSISTANT, "Health is down.");

        assertThat(messages.findAllByConversationIdAndConversationUserIdOrderByIdAsc(chat.getId(), alice.getId()))
                .extracting(AiMessage::getContent).containsExactly("Is anything down?", "Health is down.");
        assertThat(messages.findAllByConversationIdAndConversationUserIdOrderByIdAsc(chat.getId(), bob.getId()))
                .isEmpty();
    }

    @Test
    void returnsTheNewestMessagesForThePromptHistory() {
        User alice = newUser("alice@example.com");
        AiConversation chat = conversation(alice, "Chat", Instant.now());
        for (int i = 1; i <= 5; i++) {
            message(chat, i % 2 == 1 ? MessageRole.USER : MessageRole.ASSISTANT, "message " + i);
        }

        List<AiMessage> newest = messages.findAllByConversationIdAndConversationUserIdOrderByIdDesc(
                chat.getId(), alice.getId(), PageRequest.of(0, 3));

        assertThat(newest).extracting(AiMessage::getContent).containsExactly("message 5", "message 4", "message 3");
        assertThat(messages.countByConversationId(chat.getId())).isEqualTo(5);
    }

    @Test
    void findsAMessageForFeedbackOnlyForTheOwner() {
        User alice = newUser("alice@example.com");
        User bob = newUser("bob@example.com");
        AiMessage answer = message(conversation(alice, "Chat", Instant.now()), MessageRole.ASSISTANT, "Answer");

        assertThat(messages.findByIdAndConversationUserId(answer.getId(), alice.getId())).isPresent();
        assertThat(messages.findByIdAndConversationUserId(answer.getId(), bob.getId())).isEmpty();
    }

    @Test
    void storesTokenCountsAndModelOnAnswers() {
        AiConversation chat = conversation(newUser("alice@example.com"), "Chat", Instant.now());
        AiMessage answer = new AiMessage(chat, MessageRole.ASSISTANT, "Stopped half", MessageStatus.PARTIAL);
        answer.setInputTokens(812);
        answer.setOutputTokens(40);
        answer.setModel("gemini-3.1-flash-lite");
        messages.saveAndFlush(answer);
        em.clear();

        AiMessage stored = messages.findById(answer.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(MessageStatus.PARTIAL);
        assertThat(stored.getInputTokens()).isEqualTo(812);
        assertThat(stored.getOutputTokens()).isEqualTo(40);
        assertThat(stored.getModel()).isEqualTo("gemini-3.1-flash-lite");
    }

    @Test
    void deletingAConversationDeletesItsMessagesAndFeedback() {
        AiConversation chat = conversation(newUser("alice@example.com"), "Chat", Instant.now());
        AiMessage answer = message(chat, MessageRole.ASSISTANT, "Answer");
        feedback.saveAndFlush(new AiMessageFeedback(answer.getId(), AiMessageFeedback.THUMBS_UP));
        em.clear();

        conversations.deleteById(chat.getId());
        conversations.flush();
        em.clear();

        assertThat(messages.count()).isZero();
        assertThat(feedback.count()).isZero();
    }

    @Test
    void deletingTheUserDeletesAllTheirChats() {
        User alice = newUser("alice@example.com");
        message(conversation(alice, "Chat", Instant.now()), MessageRole.USER, "Hi");
        em.clear();

        em.getEntityManager().createNativeQuery("DELETE FROM users WHERE id = :id")
                .setParameter("id", alice.getId()).executeUpdate();
        em.clear();

        assertThat(conversations.count()).isZero();
        assertThat(messages.count()).isZero();
    }

    @Test
    void acceptsOnlyThumbsUpOrDown() {
        AiMessage answer = message(conversation(newUser("alice@example.com"), "Chat", Instant.now()),
                MessageRole.ASSISTANT, "Answer");

        assertThatThrownBy(() -> feedback.saveAndFlush(new AiMessageFeedback(answer.getId(), (short) 0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void keepsOneRatingPerMessageAndReplacesIt() {
        AiMessage answer = message(conversation(newUser("alice@example.com"), "Chat", Instant.now()),
                MessageRole.ASSISTANT, "Answer");
        feedback.saveAndFlush(new AiMessageFeedback(answer.getId(), AiMessageFeedback.THUMBS_UP));

        feedback.saveAndFlush(new AiMessageFeedback(answer.getId(), AiMessageFeedback.THUMBS_DOWN));
        em.clear();

        assertThat(feedback.count()).isEqualTo(1);
        assertThat(feedback.findById(answer.getId())).get()
                .extracting(AiMessageFeedback::getRating).isEqualTo(AiMessageFeedback.THUMBS_DOWN);
    }

    private AiConversation conversation(User owner, String title, Instant updatedAt) {
        AiConversation chat = new AiConversation(owner, title);
        chat.setUpdatedAt(updatedAt);
        return em.persistAndFlush(chat);
    }

    private AiMessage message(AiConversation chat, MessageRole role, String content) {
        return em.persistAndFlush(new AiMessage(chat, role, content, MessageStatus.COMPLETE));
    }
}
