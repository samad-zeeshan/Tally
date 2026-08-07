// Fraud scores for the selected account's payments out, and which rules fired. Scoring runs after the
// transfer commits, so a score can land a moment after the statement does; the panel asks
// once more shortly after each refresh rather than polling.
import { useEffect, useState } from "react";
import { ApiError, getRisk } from "../api/client";
import type { RiskReport } from "../api/types";
import { formatMinor } from "../lib/money";
import { formatDateTime, timeAgo } from "../lib/format";
import { riskLevel, ruleLabel } from "../lib/risk";

interface Props {
  accountId: string;
  names: Map<string, string>;
  refreshSeq: number;
}

const RECHECK_MS = 800;
const SHOWN = 5;

export function RiskPanel({ accountId, names, refreshSeq }: Props) {
  const [report, setReport] = useState<RiskReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  // A 404 for an account that exists means the backend has no risk endpoint, as with the static demo's
  // stand-in server. The panel then stays out of the way instead of showing an error.
  const [unavailable, setUnavailable] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const load = () =>
      getRisk(accountId)
        .then((loaded) => {
          if (!cancelled) {
            setReport(loaded);
            setError(null);
          }
        })
        .catch((caught: ApiError) => {
          if (cancelled) {
            return;
          }
          if (caught.status === 404) {
            setUnavailable(true);
          } else {
            setError(caught.message);
          }
        });
    load();
    const timer = window.setTimeout(load, RECHECK_MS);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [accountId, refreshSeq]);

  if (unavailable) {
    return null;
  }

  const scores = report ? report.scores.slice(0, SHOWN) : [];
  const now = Date.now();

  return (
    <div className="risk">
      <h3 className="risk-title">Fraud checks on payments out</h3>
      <p className="risk-caption">Scored after each payment is written. Advice only: a score never stops a payment.</p>
      {error && (
        <p className="notice notice-error" role="alert">
          {error}
        </p>
      )}
      {report && scores.length === 0 && <p className="risk-empty">No payments out yet.</p>}
      {report && scores.length > 0 && (
        <ul className="risk-list">
          {scores.map((score) => {
            const level = riskLevel(score.score, report.flagThreshold);
            const payee = names.get(score.counterpartyAccountId) ?? `${score.counterpartyAccountId.slice(0, 8)}…`;
            return (
              <li key={score.postingId} className="risk-row">
                <span
                  className={`risk-score ${level}`}
                  title={`Score ${score.score} of 100. Flagged at ${report.flagThreshold}.`}
                >
                  {score.score}
                </span>
                <span className="risk-what">
                  <span className="risk-payment">
                    {formatMinor(score.amountMinor)} to {payee}
                  </span>
                  <time dateTime={score.eventAt} title={formatDateTime(score.eventAt)}>
                    {timeAgo(score.eventAt, now)}
                  </time>
                </span>
                <span className="risk-rules">
                  {score.rules.length === 0 ? (
                    <span className="risk-rule none">no rule fired</span>
                  ) : (
                    score.rules.map((rule) => (
                      <span key={rule} className="risk-rule">
                        {ruleLabel(rule)}
                      </span>
                    ))
                  )}
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
