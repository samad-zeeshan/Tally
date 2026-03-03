package dev.tally.store;

import dev.tally.core.Account;
import dev.tally.core.AccountId;
import dev.tally.core.Ledger;
import dev.tally.core.Posting;
import dev.tally.core.Transfer;
import dev.tally.core.TransferOutcome;
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

    // The opening is funded by world, not minted, through the same Ledger.post ordinary transfers
    // use. The account insert and the world debit are one unit: the account enters the map only
    // inside the Posted result, so a half-funded account is never observable.
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
        Map<AccountId, Account> snapshot = Map.of(world.id(), world, fresh.id(), fresh);
        Transfer funding = Transfer.between(WorldAccount.ID, fresh.id(), openingBalanceMinor);
        if (!(Ledger.post(funding, snapshot) instanceof Ledger.Result.Posted(var updated))) {
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

    @Override
    public TransferOutcome apply(Transfer transfer) {
        // Reserved guard first, before any lookup: only the internal opening path may name world.
        for (Posting p : transfer.postings()) {
            if (WorldAccount.isWorld(p.accountId())) {
                return new TransferOutcome.ReservedAccount(WorldAccount.ID);
            }
        }
        Map<AccountId, Account> snapshot = new HashMap<>();
        for (Posting p : transfer.postings()) {
            Account account = accounts.get(p.accountId());
            if (account == null) {
                return new TransferOutcome.UnknownAccount(p.accountId());
            }
            snapshot.put(p.accountId(), account);
        }
        return switch (Ledger.post(transfer, snapshot)) {
            case Ledger.Result.Posted(var updated) -> {
                for (Account a : updated) {
                    accounts.put(a.id(), a);
                }
                yield applied(transfer, updated);
            }
            case Ledger.Result.InsufficientFunds(var account, var balance, var requested) ->
                    new TransferOutcome.InsufficientFunds(account, balance, requested);
            // A store-built transfer is always balanced, so an Invalid here is a bug, not an outcome.
            case Ledger.Result.Invalid(var reason) ->
                    throw new IllegalStateException("store-built transfer did not balance: " + reason);
        };
    }

    // For a two-party transfer, from is the debit (negative) posting's account, to the credit.
    private static TransferOutcome applied(Transfer transfer, java.util.List<Account> updated) {
        AccountId fromId = null;
        AccountId toId = null;
        for (Posting p : transfer.postings()) {
            if (p.amountMinor() < 0) {
                fromId = p.accountId();
            } else if (p.amountMinor() > 0) {
                toId = p.accountId();
            }
        }
        return new TransferOutcome.Applied(transfer.id(),
                balanceOf(updated, fromId), balanceOf(updated, toId), Instant.now());
    }

    private static long balanceOf(java.util.List<Account> updated, AccountId id) {
        for (Account a : updated) {
            if (a.id().equals(id)) {
                return a.balanceMinor();
            }
        }
        throw new IllegalStateException("expected account in updated set: " + id);
    }
}
