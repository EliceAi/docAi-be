CREATE TABLE users (
    user_id UUID PRIMARY KEY,
    device_id_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL
);
