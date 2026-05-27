package dev.tally.testsupport;

import dev.tally.core.Account;
import dev.tally.core.AccountId;
import dev.tally.core.Ledger;
import dev.tally.core.ReconciliationReport;
import dev.tally.core.StatementPage;
import dev.tally.core.Transfer;
import dev.tally.core.TransferId;
import dev.tally.core.TransferOutcome;
import dev.tally.core.TransferRequest;
import dev.tally.core.WorldAccount;
import dev.tally.store.Store;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The store as it stood before any lock, preserved in test scope as the race demo's subject.
 *
 * Both maps are concurrent, so a probe never crashes, and the get-then-put idempotency on a
 * thread-safe map is still a race. The account read-check-write has no mutual exclusion, so
 * two threads lose each other's update. That is the point; do not add a lock here.
 */
public final class NaiveInMemoryStore implements Store {
    private final Map<AccountId, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, TransferOutcome> idempotency = new ConcurrentHashMap<>();

    public NaiveInMemoryStore() {
        accounts.put(WorldAccount.ID, WorldAccount.initial());
    }

    @Override
    public Account createAccount(String name, long openingBalanceMinor) {
        if (openingBalanceMinor < 0) {
            throw new IllegalArgumentException("opening balance must not be negative: " + openingBalanceMinor);
        }
        Account fresh = new Account(AccountId.newId(), name, 0L, false, Instant.now());
        if (openingBalanceMinor == 0) {
            accounts.put(fresh.id(), fresh);
            return fresh;
        }
        Account world = accounts.get(WorldAccount.ID);
        Transfer funding = Transfer.between(WorldAccount.ID, fresh.id(), openingBalanceMinor);
        if (!(Ledger.post(funding, Map.of(world.id(), world, fresh.id(), fresh))
                instanceof Ledger.Result.Posted(var updated))) {
            throw new IllegalStateException("world funding should always post");
        }
        for (Account a : updated) {
            accounts.put(a.id(), a);
        }
        return accounts.get(fresh.id());
    }

    @Override
    public Optional<Account> findAccount(AccountId id) {
        return Optional.ofNullable(accounts.get(id));
    }

    // The race demo drives transfers and reads balances, never the account listing.
    @Override
    public List<Account> listAccounts() {
        throw new UnsupportedOperationException("the race demo store does not list accounts");
    }

    // The race demo drives transfers and reads balances, never statements.
    @Override
    public StatementPage statement(AccountId id, long beforePostingId, int limit) {
        return new StatementPage(id, List.of(), false);
    }

    // A store built to lose updates cannot be audited; the demo never asks it to.
    @Override
    public ReconciliationReport reconcile() {
        throw new UnsupportedOperationException("the race demo store does not reconcile");
    }

    @Override
    public TransferOutcome apply(TransferRequest request) {
        if (request.from().equals(request.to())) {
            throw new IllegalArgumentException("same account");
        }
        if (request.amountMinor() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        // Get-then-put on a thread-safe map: each op is atomic, the sequence still races, so two
        // threads with one key both see it absent and both apply.
        TransferOutcome recorded = idempotency.get(request.idempotencyKey());
        if (recorded != null) {
            return new TransferOutcome.Replayed(recorded);
        }
        Account from = accounts.get(request.from());
        if (from == null) {
            return new TransferOutcome.UnknownAccount(request.from());
        }
        Account to = accounts.get(request.to());
        if (to == null) {
            return new TransferOutcome.UnknownAccount(request.to());
        }
        if (WorldAccount.isWorld(request.from())) {
            return new TransferOutcome.ReservedAccount(request.from());
        }
        if (WorldAccount.isWorld(request.to())) {
            return new TransferOutcome.ReservedAccount(request.to());
        }
        // Unsynchronized read-modify-write: two threads read the same balance and the second put
        // erases the first. This is the lost update the whole stage exists to fix.
        long amount = request.amountMinor();
        long newFrom = Math.subtractExact(from.balanceMinor(), amount);
        if (newFrom < 0 && !from.allowNegative()) {
            TransferOutcome rejection = new TransferOutcome.InsufficientFunds(from.id(), from.balanceMinor(), amount);
            idempotency.put(request.idempotencyKey(), rejection);
            return rejection;
        }
        long newTo = Math.addExact(to.balanceMinor(), amount);
        accounts.put(from.id(), new Account(from.id(), from.name(), newFrom, from.allowNegative(), from.createdAt()));
        accounts.put(to.id(), new Account(to.id(), to.name(), newTo, to.allowNegative(), to.createdAt()));
        TransferOutcome applied = new TransferOutcome.Applied(TransferId.newId(), newFrom, newTo, Instant.now(), 0, 0);
        idempotency.put(request.idempotencyKey(), applied);
        return applied;
    }
}
