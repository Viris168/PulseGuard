-- A plain-English summary of the incident written by the AI model, cached so a page view
-- doesn't cost a model call. ai_summary_at tells whether it predates the resolution (then it
-- is rewritten once) or, for an open incident, whether it is old enough to refresh.
ALTER TABLE incidents ADD COLUMN ai_summary TEXT;
ALTER TABLE incidents ADD COLUMN ai_summary_at TIMESTAMPTZ;
