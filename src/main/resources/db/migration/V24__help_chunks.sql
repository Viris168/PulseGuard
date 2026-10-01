-- Ask AI's help docs (AI_MILESTONE_3.md): the articles in src/main/resources/help/, cut into one
-- chunk per "##" section, each with its embedding for meaning search and a tsvector for exact
-- words. Product documentation, the same for every user: no user_id, by design.
--
-- Needs the pgvector image (pgvector/pgvector:pg16). CREATE EXTENSION needs a superuser, which
-- the app's POSTGRES_USER is in docker-compose.yml, deploy/docker-compose.yml and Testcontainers.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE help_chunks (
    id              BIGSERIAL    PRIMARY KEY,
    article         VARCHAR(80)  NOT NULL,         -- slug, e.g. 'slack-alerts'
    title           VARCHAR(200) NOT NULL,         -- the article's title
    heading         VARCHAR(200) NOT NULL,         -- the "##" section
    anchor          VARCHAR(120) NOT NULL,         -- for /docs/slack-alerts#setting-it-up
    position        INT          NOT NULL,         -- the section's place in its article
    content         TEXT         NOT NULL,
    -- sha-256 of the embedding model and title, heading and content: an unchanged chunk keeps its
    -- vector; changing any of them (or switching model) makes a new hash and a new embedding.
    content_hash    CHAR(64)     NOT NULL,
    embedding_model VARCHAR(60)  NOT NULL,
    embedding       vector(768)  NOT NULL,
    search_text     tsvector GENERATED ALWAYS AS
                    (to_tsvector('english', title || ' ' || heading || ' ' || content)) STORED,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (article, anchor)
);

-- Cosine distance: gemini-embedding-001 vectors at 768 dimensions aren't normalized (Step 1).
CREATE INDEX idx_help_chunks_embedding ON help_chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_help_chunks_search    ON help_chunks USING gin (search_text);
