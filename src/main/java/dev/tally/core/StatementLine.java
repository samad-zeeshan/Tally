package dev.tally.core;

import java.time.Instant;

/**
 * One posting on a statement, from the statement account's side.
 *
 * counterpartyAccountId is the other account in the transfer, world for an opening. amountMinor
 * is signed: negative is money out. postingId is the monotonic cursor.
 */
public record StatementLine(long postingId, TransferId transferId, AccountId counterpartyAccountId,
                            long amountMinor, long balanceAfterMinor, Instant createdAt) {}
