package dev.tally.store;

import dev.tally.core.Account;
import dev.tally.core.AccountId;

import java.util.Optional;

/**
 * The one swappable seam between the service and its storage.
 *
 * Each implementation owns its own concurrency control and idempotency, because the
 * transaction boundary and locking are storage specific. It grows across stages; a Postgres
 * implementation reimplements the same contract.
 */
public interface Store {
    Account createAccount(String name, long openingBalanceMinor);

    Optional<Account> findAccount(AccountId id);
}
