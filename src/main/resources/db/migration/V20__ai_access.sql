-- Ask AI consent (AI_PLAN.md, option B). Off until the user turns it on; then either every
-- monitor (including ones added later) or only the listed ones may be sent to the AI provider.
-- No row means off. Deleting a user or a monitor removes its rows.
CREATE TABLE ai_access (
    user_id      BIGINT      PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    enabled      BOOLEAN     NOT NULL DEFAULT FALSE,
    all_monitors BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ai_access_monitors (
    user_id    BIGINT NOT NULL REFERENCES ai_access(user_id) ON DELETE CASCADE,
    monitor_id BIGINT NOT NULL REFERENCES monitors(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, monitor_id)
);
