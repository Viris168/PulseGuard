-- The help-doc sections a search_help_docs call showed the model (AI_MILESTONE_3.md, Step 5), so a
-- reopened answer still links to its sources. A JSON array of {"title", "url"}; NULL for every
-- other tool and for calls saved before this column existed.
ALTER TABLE ai_tool_calls ADD COLUMN sources TEXT;
