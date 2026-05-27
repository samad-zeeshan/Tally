package dev.tally.fraud;

import dev.tally.core.AccountId;
import dev.tally.core.TransferId;

import java.time.Instant;

/**
 * One applied transfer, as the scorer sees it: the debit posting it scores, and the credit beside it.
 */
public record PostingEvent(long debitPostingId, long creditPostingId, TransferId transferId,
                           AccountId from, AccountId to, long amountMinor, Instant at) {}
