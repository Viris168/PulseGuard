-- A chat started from a monitor or incident page (AI_MILESTONE_2.md, Step 5) remembers which one,
-- so every turn can tell the model what "this incident" or "it" means. Deleting the monitor or
-- incident keeps the chat and just drops the context.
ALTER TABLE ai_conversations ADD COLUMN context_monitor_id  BIGINT REFERENCES monitors(id)  ON DELETE SET NULL;
ALTER TABLE ai_conversations ADD COLUMN context_incident_id BIGINT REFERENCES incidents(id) ON DELETE SET NULL;
