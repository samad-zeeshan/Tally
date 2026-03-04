package dev.tally.store;

import dev.tally.core.Account;
import dev.tally.core.AccountId;
import dev.tally.core.Ledger;
import dev.tally.core.Transfer;
import dev.tally.core.TransferId;
import dev.tally.core.TransferOutcome;
import dev.tally.core.TransferRequest;
import dev.tally.core.WorldAccount;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store: idempotent by a reservation map, not yet safe on the balance path.
 *
 * The account map is concurrent, so unlocked readers see a whole, current Account and the
 * map cannot be corrupted. But the balance read-check-write still spans two map operations
 * with no mutual exclusion, so a lost update is still possible. Locks land in the next commits.
 */
public final class InMemoryStore implements Store {
    private final Map<AccountId, Account> accounts = new ConcurrentHashMap<>();

    private record Reservation(TransferRequest request, CompletableFuture<TransferOutcome> slot) {}
    private final ConcurrentHashMap<String, Reservation> reservations = new ConcurrentHashMap<>();

    private sealed interface Claim {
        record Winner(CompletableFuture<TransferOutcome> slot) implements Claim {}
        record Replay(CompletableFuture<TransferOutcome> slot) implements Claim {}
        record Conflict() implements Claim {}
    }

    public InMemoryStore() {
        // World exists from construction: the reserved counterparty that funds every opening.
        accounts.put(WorldAccount.ID, WorldAccount.initial());
    }

    // The opening funds from world through the same Ledger.post ordinary transfers use. Its id is
    // fresh, so its system key open:<id> could never replay; persisting that key is a Stage 4 concern.
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
    public TransferOutcome apply(TransferRequest request) {
        // Same-account nets to a no-op and would break the strict lock order; a non-positive amount is
        // never valid. Both are HTTP-edge 400s later, so here they throw before any key claim, which
        // means a rejected structural request never consumes the key.
        if (request.from().equals(request.to())) {
            throw new IllegalArgumentException("same account");
        }
        if (request.amountMinor() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        return switch (claim(request)) {
            case Claim.Winner(var slot) -> {
                TransferOutcome outcome;
                try {
                    outcome = evaluate(request);
                } catch (RuntimeException | Error crash) {
                    // A crash is not a terminal outcome; release so a retry can rerun.
                    reservations.remove(request.idempotencyKey());
                    slot.completeExceptionally(crash);
                    throw crash;
                }
                slot.complete(outcome);   // any in-flight duplicate still gets the answer
                // Selective release: only Applied and InsufficientFunds are recorded and replay.
                if (!consumes(outcome)) {
                    reservations.remove(request.idempotencyKey());
                }
                yield outcome;
            }
            case Claim.Replay(var slot) -> {
                TransferOutcome first = slot.join();
                // Replayed wraps only a recorded outcome. A duplicate that joined a non-consuming
                // outcome inside the release window gets the raw outcome, never Replayed(UnknownAccount).
                yield consumes(first) ? new TransferOutcome.Replayed(first) : first;
            }
            case Claim.Conflict() -> new TransferOutcome.KeyConflict(request.idempotencyKey());
        };
    }

    private Claim claim(TransferRequest request) {
        Reservation fresh = new Reservation(request, new CompletableFuture<>());
        Reservation existing = reservations.putIfAbsent(request.idempotencyKey(), fresh);
        if (existing == null) {
            return new Claim.Winner(fresh.slot());
        }
        boolean sameTuple = existing.request().from().equals(request.from())
                && existing.request().to().equals(request.to())
                && existing.request().amountMinor() == request.amountMinor();
        return sameTuple ? new Claim.Replay(existing.slot()) : new Claim.Conflict();
    }

    private static boolean consumes(TransferOutcome outcome) {
        return outcome instanceof TransferOutcome.Applied
                || outcome instanceof TransferOutcome.InsufficientFunds;
    }

    // Existence and identity are decided before any balance work: they touch no account state.
    private TransferOutcome evaluate(TransferRequest request) {
        if (accounts.get(request.from()) == null) {
            return new TransferOutcome.UnknownAccount(request.from());
        }
        if (accounts.get(request.to()) == null) {
            return new TransferOutcome.UnknownAccount(request.to());
        }
        // A client may never name world; the one legitimate world transfer is the internal opening.
        if (WorldAccount.isWorld(request.from())) {
            return new TransferOutcome.ReservedAccount(request.from());
        }
        if (WorldAccount.isWorld(request.to())) {
            return new TransferOutcome.ReservedAccount(request.to());
        }
        // Commit 8: no lock yet. Commit 10 wraps this in a coarse lock, commit 11 in per-account locks.
        return applyBalances(request);
    }

    private TransferOutcome applyBalances(TransferRequest request) {
        Account from = accounts.get(request.from());
        Account to = accounts.get(request.to());
        Transfer transfer = Transfer.between(request.from(), request.to(), request.amountMinor());
        Map<AccountId, Account> snapshot = Map.of(from.id(), from, to.id(), to);
        return switch (Ledger.post(transfer, snapshot)) {
            case Ledger.Result.Posted(var updated) -> {
                for (Account a : updated) {
                    accounts.put(a.id(), a);
                }
                yield new TransferOutcome.Applied(transfer.id(),
                        balanceOf(updated, request.from()), balanceOf(updated, request.to()), Instant.now());
            }
            case Ledger.Result.InsufficientFunds(var account, var balance, var requested) ->
                    new TransferOutcome.InsufficientFunds(account, balance, requested);
            // A mirrored two-posting transfer can never be unbalanced, so an Invalid here is a bug.
            case Ledger.Result.Invalid(var reason) ->
                    throw new IllegalStateException("mirrored transfer did not balance: " + reason);
        };
    }

    private static long balanceOf(List<Account> updated, AccountId id) {
        for (Account a : updated) {
            if (a.id().equals(id)) {
                return a.balanceMinor();
            }
        }
        throw new IllegalStateException("expected account in updated set: " + id);
    }
}
