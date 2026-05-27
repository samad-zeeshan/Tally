// Wire types mirroring the REST contract. The field spellings must match the backend as Stages 3, 5,
// and 6 built it: camelCase, ids as opaque lowercase UUID strings, amounts as integer minor units. If a
// name ever differs, the backend wins and this file changes.

export type MinorUnits = number; // always an integer, guarded at parse time, never fractional math

export interface Account {
  id: string;
  name: string;
  balanceMinor: MinorUnits;
  createdAt: string;
}

export interface TransferRequest {
  fromAccountId: string;
  toAccountId: string;
  amountMinor: MinorUnits;
}

export interface Transfer {
  id: string;
  fromAccountId: string;
  toAccountId: string;
  amountMinor: MinorUnits;
  createdAt: string;
}

export interface StatementEntry {
  postingId: number; // bigserial long; the keyset cursor derives from it
  transferId: string;
  counterpartyAccountId: string; // world for an opening entry
  amountMinor: MinorUnits; // signed from the viewed account's perspective
  balanceAfterMinor: MinorUnits;
  createdAt: string;
}

export interface StatementPage {
  accountId: string;
  entries: StatementEntry[];
  nextCursor: string | null;
}

export interface Drift {
  accountId: string;
  storedBalanceMinor: MinorUnits;
  derivedBalanceMinor: MinorUnits;
  driftMinor: MinorUnits;
}

export interface ReconciliationReport {
  consistent: boolean;
  globalSumMinor: MinorUnits;
  accountsChecked: number;
  drifts: Drift[];
}

export interface RiskScore {
  postingId: number;
  transferId: string;
  counterpartyAccountId: string;
  amountMinor: MinorUnits; // the payment out, always positive
  score: number; // 0 to 100, points not money
  flagged: boolean;
  rules: string[];
  eventAt: string;
  scoredAt: string;
}

export interface RiskReport {
  accountId: string;
  flagThreshold: number;
  scores: RiskScore[];
}

// The wire envelope is {"error": {...}}; this is the INNER object only. client.ts reads response.error into it.
export interface ApiErrorBody {
  code: string;
  message: string;
  field?: string; // present on validation errors
  requestId?: string; // present from Stage 6 on
}
