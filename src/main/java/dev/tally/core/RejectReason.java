package dev.tally.core;

/**
 * The two ways a hand-built posting list can be arithmetically invalid, internal to the Ledger.
 *
 * A transfer the store builds is always a balanced two-posting movement, so neither can occur
 * on the store path. If one ever reached a client it is a bug, a 500, never an outcome.
 */
public enum RejectReason {
    UNBALANCED,
    OVERFLOW
}
