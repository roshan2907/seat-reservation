-- Shows: price in integer paise, per-user seat limit
CREATE TABLE shows (
                       id              UUID PRIMARY KEY,
                       name            TEXT        NOT NULL,
                       price_paise     BIGINT      NOT NULL CHECK (price_paise >= 0),
                       per_user_limit  INT         NOT NULL DEFAULT 4 CHECK (per_user_limit > 0),
                       total_seats     INT         NOT NULL CHECK (total_seats > 0),
                       created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Reservations: one row per successful reserve; idempotency enforced by unique key
CREATE TABLE reservations (
                              id               UUID PRIMARY KEY,
                              show_id          UUID        NOT NULL REFERENCES shows(id),
                              user_id          TEXT        NOT NULL,
                              idempotency_key  TEXT        NOT NULL,
                              request_hash     TEXT        NOT NULL,
                              seats            TEXT[]      NOT NULL,
                              amount_paise     BIGINT      NOT NULL CHECK (amount_paise >= 0),
                              status           TEXT        NOT NULL CHECK (status IN ('confirmed', 'cancelled')),
                              created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
                              CONSTRAINT uq_reservation_idempotency UNIQUE (user_id, idempotency_key)
);

-- Seats: exactly one row per seat, exactly one status at a time
CREATE TABLE seats (
                       show_id         UUID NOT NULL REFERENCES shows(id),
                       label           TEXT NOT NULL,
                       status          TEXT NOT NULL CHECK (status IN ('available', 'held', 'confirmed')),
                       reservation_id  UUID REFERENCES reservations(id),
                       PRIMARY KEY (show_id, label),
                       CONSTRAINT chk_seat_owner CHECK ((status = 'available') = (reservation_id IS NULL))
);

-- Per-user seat count per show; updated atomically to enforce the limit
CREATE TABLE user_show_quota (
                                 show_id     UUID NOT NULL REFERENCES shows(id),
                                 user_id     TEXT NOT NULL,
                                 held_count  INT  NOT NULL DEFAULT 0 CHECK (held_count >= 0),
                                 PRIMARY KEY (show_id, user_id)
);

CREATE INDEX idx_seats_reservation ON seats (reservation_id);
CREATE INDEX idx_reservations_show ON reservations (show_id);