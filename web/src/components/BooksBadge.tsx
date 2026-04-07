// The double-entry pulse in the header: after every change it asks /reconciliation whether the whole
// book still sums to zero and shows the answer. Reconciliation needs the token, so when the request
// fails (no token, backend down) the badge steps aside instead of alarming.
import { useEffect, useState } from "react";
import { getReconciliation } from "../api/client";
import type { ReconciliationReport } from "../api/types";
import { formatMinor } from "../lib/money";

export function BooksBadge({ refreshSeq }: { refreshSeq: number }) {
  const [report, setReport] = useState<ReconciliationReport | null>(null);
  const [hidden, setHidden] = useState(false);

  useEffect(() => {
    let cancelled = false;
    getReconciliation()
      .then((fresh) => {
        if (!cancelled) {
          setReport(fresh);
          setHidden(false);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setHidden(true);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [refreshSeq]);

  if (hidden || report === null) {
    return null;
  }
  const balanced = report.consistent;
  return (
    <span
      className={"badge-books" + (balanced ? "" : " drift")}
      title={`${report.accountsChecked} accounts checked, every balance recomputed from its postings`}
    >
      <span className={"dot" + (balanced ? " pulse" : "")} aria-hidden="true" />
      {balanced ? `Σ ${formatMinor(report.globalSumMinor)} · books balanced` : `drift on ${report.drifts.length} account(s)`}
    </span>
  );
}
