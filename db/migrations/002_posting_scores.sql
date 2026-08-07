-- Fraud scores, one row per scored debit posting, written by the asynchronous scorer.

CREATE TABLE posting_scores (
    -- The primary key is the idempotency guarantee for scoring: a posting delivered twice is inserted
    -- once, with ON CONFLICT DO NOTHING, whichever replica scored it.
    posting_id      bigint PRIMARY KEY REFERENCES postings(id),
    transfer_id     uuid NOT NULL REFERENCES transfers(id),
    account_id      uuid NOT NULL REFERENCES accounts(id),
    counterparty_id uuid NOT NULL REFERENCES accounts(id),
    amount_minor    bigint NOT NULL CHECK (amount_minor > 0),
    score           integer NOT NULL CHECK (score BETWEEN 0 AND 100),
    fired_rules     text[] NOT NULL,
    -- The time the rules saw. It is the ledger's commit time unless the evaluation replay clock is on.
    event_at        timestamptz NOT NULL,
    scored_at       timestamptz NOT NULL DEFAULT now()
);

-- The scorer reads an account's window newest first before every score, and the risk endpoint reads
-- the same range, so both are one index scan.
CREATE INDEX posting_scores_account_posting ON posting_scores (account_id, posting_id DESC);
-- Money arriving at an account, for rules that look at what came in before it went out.
CREATE INDEX posting_scores_counterparty_event ON posting_scores (counterparty_id, event_at);
