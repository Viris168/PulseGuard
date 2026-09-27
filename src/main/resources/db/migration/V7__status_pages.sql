-- One public status page per account (PRD 2.6), served at /api/status/{slug} without auth.
CREATE TABLE status_pages (
    id           BIGSERIAL    PRIMARY KEY,
    user_id      BIGINT       NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    slug         VARCHAR(40)  NOT NULL UNIQUE,
    title        VARCHAR(80)  NOT NULL,
    description  VARCHAR(280) NOT NULL DEFAULT '',
    published    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The monitors a page shows, in order, under a public name. Deleting a monitor removes it
-- from the page (CASCADE); the index keeps that delete from scanning this table.
CREATE TABLE status_page_monitors (
    status_page_id  BIGINT       NOT NULL REFERENCES status_pages(id) ON DELETE CASCADE,
    monitor_id      BIGINT       NOT NULL REFERENCES monitors(id) ON DELETE CASCADE,
    display_name    VARCHAR(100) NOT NULL,
    position        INT          NOT NULL,
    PRIMARY KEY (status_page_id, monitor_id)
);

CREATE INDEX idx_status_page_monitors_monitor ON status_page_monitors (monitor_id);
