-- Ask AI tools (AI_MILESTONE_2.md): every lookup the model made while writing an answer, saved
-- with that answer. It is what the "Checked …" lines under an answer come from, and what shows why
-- an answer went wrong ("it looked up the wrong month"). Deleted with the answer, and so with the
-- chat and the user.
CREATE TABLE ai_tool_calls (
    id          BIGSERIAL    PRIMARY KEY,
    message_id  BIGINT       NOT NULL REFERENCES ai_messages(id) ON DELETE CASCADE,
    tool        VARCHAR(50)  NOT NULL,
    arguments   TEXT         NOT NULL,   -- as the model sent them, cut to 500 characters
    result      TEXT,                    -- the first 1,000 characters
    ok          BOOLEAN      NOT NULL,   -- false: refused (limit), failed or timed out
    duration_ms INT          NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_ai_tool_calls_message ON ai_tool_calls (message_id, id);
