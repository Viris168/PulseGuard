-- Ask AI chat (AI_MILESTONE_1.md): saved conversations, their messages, and thumbs up/down.
-- Deleting a user removes their conversations; deleting a conversation removes its messages
-- and their feedback. Nothing here is shared between users.

CREATE TABLE ai_conversations (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title       VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- The conversation list: one user's chats, most recently used first.
CREATE INDEX idx_ai_conversations_user ON ai_conversations (user_id, updated_at DESC);

CREATE TABLE ai_messages (
    id               BIGSERIAL    PRIMARY KEY,
    conversation_id  BIGINT       NOT NULL REFERENCES ai_conversations(id) ON DELETE CASCADE,
    role             VARCHAR(10)  NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content          TEXT         NOT NULL,
    -- COMPLETE: answered in full. PARTIAL: stopped or disconnected. FAILED: the model gave no answer.
    status           VARCHAR(10)  NOT NULL CHECK (status IN ('COMPLETE', 'PARTIAL', 'FAILED')),
    -- Reported by the provider for assistant messages; null when it didn't say.
    input_tokens     INT,
    output_tokens    INT,
    model            VARCHAR(100),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- A conversation's messages in order; ids grow, so they order messages within a conversation.
CREATE INDEX idx_ai_messages_conversation ON ai_messages (conversation_id, id);

CREATE TABLE ai_message_feedback (
    message_id  BIGINT      PRIMARY KEY REFERENCES ai_messages(id) ON DELETE CASCADE,
    rating      SMALLINT    NOT NULL CHECK (rating IN (-1, 1)),  -- 1 = thumbs up, -1 = thumbs down
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
