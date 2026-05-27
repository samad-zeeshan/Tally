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
import java.util.concurrent.locks.ReentrantLock;

/**
 * A store that takes its two account locks in argument order instead of id order.
 *
 * That is the classic deadlock setup: A-to-B and B-to-A each grab one lock and wait on the
 * other. It uses lockInterruptibly so the demo test can unstick the threads once it has
 * proven the deadlock; production uses lock().
 */
public final class UnorderedLockStore implements Store {
    private final Map<AccountId, Account> accounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<AccountId, ReentrantLock> locks = new ConcurrentHashMap<>();

    public UnorderedLockStore() {
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

    // The deadlock demo drives transfers only, never the account listing.
    @Override
    public List<Account> listAccounts() {
        throw new UnsupportedOperationException("the deadlock demo store does not list accounts");
    }

    // The deadlock demo drives transfers only, never statements.
    @Override
    public StatementPage statement(AccountId id, long beforePostingId, int limit) {
        return new StatementPage(id, List.of(), false);
    }

    // The deadlock demo never reconciles; only the production stores answer this.
    @Override
    public ReconciliationReport reconcile() {
        throw new UnsupportedOperationException("the deadlock demo store does not reconcile");
    }

    @Override
    public TransferOutcome apply(TransferRequest request) {
        ReentrantLock lockFrom = lockFor(request.from());
        ReentrantLock lockTo = lockFor(request.to());
        try {
            lockFrom.lockInterruptibly();
            try {
                lockTo.lockInterruptibly();
                try {
                    return applyBalances(request);
                } finally {
                    lockTo.unlock();
                }
            } finally {
                lockFrom.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while locking", e);
        }
    }

    private TransferOutcome applyBalances(TransferRequest request) {
        Account from = accounts.get(request.from());
        Account to = accounts.get(request.to());
        long amount = request.amountMinor();
        long newFrom = Math.subtractExact(from.balanceMinor(), amount);
        long newTo = Math.addExact(to.balanceMinor(), amount);
        accounts.put(from.id(), new Account(from.id(), from.name(), newFrom, from.allowNegative(), from.createdAt()));
        accounts.put(to.id(), new Account(to.id(), to.name(), newTo, to.allowNegative(), to.createdAt()));
        return new TransferOutcome.Applied(TransferId.newId(), newFrom, newTo, Instant.now(), 0, 0);
    }

    private ReentrantLock lockFor(AccountId id) {
        return locks.computeIfAbsent(id, k -> new ReentrantLock());
    }
}
