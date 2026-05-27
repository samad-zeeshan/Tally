// Plain-words helpers for the fraud scores on the risk panel. Pure, so the wording is tested like the
// money helpers are.

export type RiskLevel = "clear" | "watch" | "flagged";

// The threshold comes from the server's response, so the panel and GET /accounts/{id}/risk never
// disagree about what counts as flagged.
export function riskLevel(score: number, threshold: number): RiskLevel {
  if (score >= threshold) {
    return "flagged";
  }
  return score > 0 ? "watch" : "clear";
}

const LABELS: Record<string, string> = {
  velocity: "many payments in 10 minutes",
  amount_deviation: "far above the usual amount",
  new_counterparty: "new payee",
  round_amount: "round amount",
  time_of_day: "unusual hour",
};

// A rule added later, by the reflection step for one, still reads sensibly before it gets a label.
export function ruleLabel(rule: string): string {
  return LABELS[rule] ?? rule.replaceAll("_", " ");
}
