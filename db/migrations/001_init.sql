-- The whole schema as one unit: accounts, transfers, postings, and the world row.

CREATE TABLE accounts (
    id             uuid PRIMARY KEY,
    name           text NOT NULL,
    -- Only world may sit below zero. Every other account is rejected under a row lock before a
    -- debit could take it negative, so this CHECK is the last line: if a bug ever computes a bad
    -- balance the write is refused loudly instead of stored.
    allow_negative boolean NOT NULL DEFAULT false,
    balance_minor  bigint NOT NULL DEFAULT 0 CHECK (balance_minor >= 0 OR allow_negative),
    created_at     timestamptz NOT NULL DEFAULT now()
);

-- The reserved world account funds every opening. Its balance is the negative of all money ever
-- issued, so SUM(balance_minor) over every row including this one stays exactly zero.
INSERT INTO accounts (id, name, allow_negative, balance_minor)
VALUES ('00000000-0000-0000-0000-000000000000', 'world', true, 0);

CREATE TABLE transfers (
    id                  uuid PRIMARY KEY,
    -- The unique constraint is the idempotency guarantee. It lives in the database, so it holds
    -- across process restarts and would hold across multiple app instances, which an application
    -- lock never could.
    idempotency_key     text NOT NULL UNIQUE,
    request_fingerprint text NOT NULL,
    -- Request fields are denormalized here as well as onto postings so a rejected transfer, which
    -- writes no postings, can still replay its first answer with no postings read.
    from_account_id     uuid NOT NULL REFERENCES accounts(id),
    to_account_id       uuid NOT NULL REFERENCES accounts(id),
    amount_minor        bigint NOT NULL CHECK (amount_minor > 0),
    status              text NOT NULL CHECK (status IN ('applied', 'insufficient_funds')),
    created_at          timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE postings (
    id                  bigserial PRIMARY KEY,
    transfer_id         uuid NOT NULL REFERENCES transfers(id),
    account_id          uuid NOT NULL REFERENCES accounts(id),
    -- Signed: debit negative, credit positive. A transfer's two postings sum to zero.
    amount_minor        bigint NOT NULL CHECK (amount_minor <> 0),
    -- The account balance right after this posting applied, written under the account lock. A
    -- historical fact, stable under pagination, and a third witness for the reconciliation check.
    balance_after_minor bigint NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now()
);

-- Serves the statement scan (WHERE account_id = ? AND id < ? ORDER BY id DESC LIMIT ?) as one
-- index range scan. The bigserial id doubles as the keyset cursor.
CREATE INDEX postings_account_id_id ON postings (account_id, id);
-- Serves the counterparty self-join: the other posting of the same transfer.
CREATE INDEX postings_transfer_id ON postings (transfer_id);
