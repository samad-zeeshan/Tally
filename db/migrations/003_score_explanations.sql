-- Each score keeps the explanation it was made with: rule points, feature values and evidence postings.

-- Plain text holding the JSON the service writes by hand, the same stance as the API. Nullable so the
-- rows scored before this migration still read back, with an empty explanation.
ALTER TABLE posting_scores ADD COLUMN explanation text;
