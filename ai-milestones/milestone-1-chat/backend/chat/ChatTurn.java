package com.viris.PulseGuard.ai.chat;

import com.viris.PulseGuard.ai.AiQuotaPolicy;
import com.viris.PulseGuard.ai.ModelStreamEvent;
import com.viris.PulseGuard.ai.ModelStreamException;
import com.viris.PulseGuard.ai.chat.dto.ChatStreamEvents;
import com.viris.PulseGuard.enumeration.MessageStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One answer being written: forwards each piece to the browser, keeps the text so far, and saves
 * the answer exactly once, however it ends:
 * <ul>
 *   <li>the model finishes: {@code COMPLETE}, then {@code done};</li>
 *   <li>the person presses Stop or leaves: {@code PARTIAL} with the text so far, model cancelled;</li>
 *   <li>the model fails after some text: {@code PARTIAL}, then {@code error};</li>
 *   <li>the model fails before any text: {@code FAILED}, the question handed back, then {@code error}.</li>
 * </ul>
 * Those can race (a Stop arriving as the last piece does), so {@link #finish} lets only the first win.
 */
public class ChatTurn {

    private static final Logger log = LoggerFactory.getLogger(ChatTurn.class);

    /** Bounds what is stored if the model ignores its length instructions. */
    static final int MAX_ANSWER_LENGTH = 4000;

    static final String FAILED_MESSAGE =
            "Ask AI couldn't answer just now. Try again in a moment; this question wasn't counted.";
    static final String TIMED_OUT_MESSAGE =
            "Ask AI took too long to answer. Try again in a moment; this question wasn't counted.";
    static final String CUT_OFF_MESSAGE =
            "Ask AI stopped partway through. What it wrote so far is kept.";

    private final Flux<ModelStreamEvent> answer;
    private final ChatMessageStore store;
    private final AiQuotaPolicy quota;
    private final Long userId;
    private final Long conversationId;
    private final Long questionId;

    private final StringBuilder text = new StringBuilder();
    private final AtomicBoolean finished = new AtomicBoolean();
    private volatile ModelStreamEvent.Finished usage;
    private volatile Disposable subscription;
    private volatile ChatEvents events;

    public ChatTurn(Flux<ModelStreamEvent> answer, ChatMessageStore store, AiQuotaPolicy quota,
                    Long userId, Long conversationId, Long questionId) {
        this.answer = answer;
        this.store = store;
        this.quota = quota;
        this.userId = userId;
        this.conversationId = conversationId;
        this.questionId = questionId;
    }

    /** Starts the model; pieces go to {@code events} as they arrive. */
    public void start(ChatEvents events) {
        this.events = events;
        subscription = answer.subscribe(this::onPiece, this::onFailure, this::onComplete);
    }

    /** The person pressed Stop or left. Keeps what was written; the question stays counted. */
    public void stop() {
        if (!finish()) {
            return;
        }
        cancelModel();
        store.saveAnswer(conversationId, textSoFar(), MessageStatus.PARTIAL, usage);
        log.info("Chat answer stopped for conversationId={}", conversationId);
        if (events != null) {
            events.close();
        }
    }

    private void onPiece(ModelStreamEvent event) {
        if (finished.get()) {
            return; // stopped: pieces still in flight are dropped
        }
        switch (event) {
            case ModelStreamEvent.Finished f -> usage = f;
            case ModelStreamEvent.Text t -> {
                synchronized (text) {
                    if (text.length() >= MAX_ANSWER_LENGTH) {
                        return;
                    }
                    text.append(t.text());
                }
                try {
                    events.delta(new ChatStreamEvents.Delta(t.text()));
                } catch (IOException | IllegalStateException gone) {
                    stop(); // the browser is gone: same as pressing Stop
                }
            }
        }
    }

    private void onComplete() {
        if (!finish()) {
            return;
        }
        String answerText = textSoFar();
        if (answerText.isBlank()) {
            failWithoutText(false);
            return;
        }
        Long answerId = store.saveAnswer(conversationId, answerText, MessageStatus.COMPLETE, usage);
        send(() -> events.done(new ChatStreamEvents.Done(questionId, answerId, MessageStatus.COMPLETE,
                quota.status(userId))));
    }

    private void onFailure(Throwable error) {
        if (!finish()) {
            return;
        }
        boolean timedOut = error instanceof ModelStreamException e && e.isTimedOut();
        if (!(error instanceof ModelStreamException)) {
            log.warn("Chat answer failed for conversationId={}: {}", conversationId, error.getClass().getSimpleName());
        }
        String answerText = textSoFar();
        if (answerText.isBlank()) {
            failWithoutText(timedOut);
            return;
        }
        Long answerId = store.saveAnswer(conversationId, answerText, MessageStatus.PARTIAL, usage);
        send(() -> events.error(new ChatStreamEvents.Error(CUT_OFF_MESSAGE, true, answerId)));
    }

    /** No answer at all: record it, and don't charge the person for it. */
    private void failWithoutText(boolean timedOut) {
        store.saveAnswer(conversationId, "", MessageStatus.FAILED, usage);
        quota.release(userId);
        send(() -> events.error(new ChatStreamEvents.Error(timedOut ? TIMED_OUT_MESSAGE : FAILED_MESSAGE, false, null)));
    }

    /** Sends the last event and ends the response; a browser already gone just misses it. */
    private void send(LastEvent event) {
        try {
            event.send();
        } catch (IOException | IllegalStateException gone) {
            log.debug("Chat client left before the last event for conversationId={}", conversationId);
        } finally {
            events.close();
        }
    }

    private boolean finish() {
        return finished.compareAndSet(false, true);
    }

    private void cancelModel() {
        Disposable s = subscription;
        if (s != null) {
            s.dispose();
        }
    }

    private String textSoFar() {
        synchronized (text) {
            return text.toString();
        }
    }

    @FunctionalInterface
    private interface LastEvent {
        void send() throws IOException;
    }
}
