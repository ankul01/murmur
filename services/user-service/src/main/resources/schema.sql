CREATE TABLE IF NOT EXISTS users (
    id              UUID         PRIMARY KEY,
    handle          VARCHAR(30)  NOT NULL UNIQUE,
    display_name    VARCHAR(50)  NOT NULL,
    follower_count  BIGINT       NOT NULL DEFAULT 0,
    following_count BIGINT       NOT NULL DEFAULT 0,
    is_celebrity    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
