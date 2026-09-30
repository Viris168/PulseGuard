package com.viris.PulseGuard.ai;

/**
 * Ask AI questions per user per UTC day. A question is counted before the model is called, so
 * two tabs asking at once can't both slip under the limit, and handed back if it gets no answer.
 * <p>
 * If the shared store is down, implementations keep counting per node (LocalFixedWindow): a
 * Redis outage must neither break Ask AI nor lift the limit.
 */
public interface AiQuestionQuota {

    /** Questions counted today. */
    long used(Long userId);

    /** Counts one question; false, and nothing counted, when {@code limit} is already reached. */
    boolean tryAcquire(Long userId, int limit);

    /** Hands back a question that got no answer. */
    void release(Long userId);
}
