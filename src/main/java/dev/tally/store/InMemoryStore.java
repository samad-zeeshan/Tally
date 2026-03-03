package dev.tally.store;

import dev.tally.core.Account;
import dev.tally.core.AccountId;
import dev.tally.core.WorldAccount;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory store, single threaded on purpose.
 *
 * A plain HashMap and no locks, so the naive version can later be shown corrupting money
 * under concurrency. Concurrency safety is added deliberately, not smuggled in here.
 */
public final class InMemoryStore implements Store {
    private final Map<AccountId, Account> accounts = new HashMap<>();

    public InMemoryStore() {
        // World exists from construction: the reserved counterparty that funds every opening.
        accounts.put(WorldAccount.ID, WorldAccount.initial());
    }

    // The opening is funded by world, not minted, so the book always sums to zero. The
    // account insert and the world debit are one unit: the account is only put once funded.
    @Override
    public Account createAccount(String name, long openingBalanceMinor) {
        if (openingBalanceMinor < 0) {
            throw new IllegalArgumentException("opening balance must not be negative: " + openingBalanceMinor);
        }
        Account account = new Account(AccountId.newId(), name, openingBalanceMinor, false, Instant.now());
        if (openingBalanceMinor > 0) {
            Account world = accounts.get(WorldAccount.ID);
            long debited = Math.subtractExact(world.balanceMinor(), openingBalanceMinor);
            accounts.put(WorldAccount.ID,
                    new Account(world.id(), world.name(), debited, world.allowNegative(), world.createdAt()));
        }
        accounts.put(account.id(), account);
        return account;
    }

    @Override
    public Optional<Account> findAccount(AccountId id) {
        return Optional.ofNullable(accounts.get(id));
    }
}
