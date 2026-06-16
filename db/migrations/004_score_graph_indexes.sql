-- Indexes for the graph reads the v2 scorer makes before every score.

-- Distinct payers of a payee below a posting id, and an account's payments since a time below one.
CREATE INDEX posting_scores_counterparty_posting ON posting_scores (counterparty_id, posting_id);
CREATE INDEX posting_scores_account_event ON posting_scores (account_id, event_at);
