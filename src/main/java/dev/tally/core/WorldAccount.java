package dev.tally.core;

import java.time.Instant;
import java.util.UUID;

/**
 * The reserved counterparty that funds every opening.
 *
 * World is the nil UUID and the one account allowed below zero, so its balance is always
 * the negative of all money issued and the whole book sums to zero.
 */
public final class WorldAccount {
    private WorldAccount() {}

    public static final AccountId ID = new AccountId(new UUID(0L, 0L));
    public static final String NAME = "world";

    // World predates every user account, so its createdAt is the epoch, deterministic for tests.
    public static Account initial() {
        return new Account(ID, NAME, 0L, true, Instant.EPOCH);
    }

    public static boolean isWorld(AccountId id) {
        return ID.equals(id);
    }
}
