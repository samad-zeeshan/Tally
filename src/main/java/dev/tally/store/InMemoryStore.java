package dev.tally.store;

import dev.tally.core.Account;
import dev.tally.core.AccountId;
import dev.tally.core.Drift;
import dev.tally.core.Ledger;
import dev.tally.core.ReconciliationReport;
import dev.tally.core.StatementLine;
import dev.tally.core.StatementPage;
import dev.tally.core.Transfer;
import dev.tally.core.TransferId;
import dev.tally.core.TransferOutcome;
import dev.tally.core.TransferRequest;
import dev.tally.core.WorldAccount;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory store: idempotent by a reservation map, concurrency-safe by per-account locks.
 *
 * Each transfer takes the two account locks lower-id-first, so disjoint transfers run in
 * parallel and a deadlock cycle cannot form. The pure Ledger does the arithmetic under those
 * locks. A Postgres store reimplements the same contract with SELECT ... FOR UPDATE.
 */
public final class InMemoryStore implements Store {
    private final Map<AccountId, Account> accounts = new ConcurrentHashMap<>();
    private final AccountLocks locks = new AccountLocks();

    // Creation order for the account listing. World is seeded in the constructor, never through
    // createAccount, so it is not here, and the list excludes it without a filter.
    private final Queue<AccountId> creationOrder = new ConcurrentLinkedQueue<>();

    // The posting journal that backs statements. Per-account, append-ordered (ascending postingId
    // because appends happen under the account lock), read as a snapshot without a lock.
    private final Map<AccountId, List<StatementLine>> journal = new ConcurrentHashMap<>();
    private final AtomicLong postingSeq = new AtomicLong(1);

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

    // The opening funds from world, not minted, through the same Ledger.post ordinary transfers use.
    // The account insert and the world debit are one unit under the world and new-account locks, so a
    // half-funded account is never observable and concurrent creations cannot lose world's debit.
    @Override
    public Account createAccount(String name, long openingBalanceMinor) {
        if (openingBalanceMinor < 0) {
            throw new IllegalArgumentException("opening balance must not be negative: " + openingBalanceMinor);
        }
        Account fresh = new Account(AccountId.newId(), name, 0L, false, Instant.now());
        Account created;
        if (openingBalanceMinor == 0) {
            accounts.put(fresh.id(), fresh);
            created = fresh;
        } else {
            created = locks.withBothLocked(WorldAccount.ID, fresh.id(), () -> {
                Account world = accounts.get(WorldAccount.ID);
                Map<AccountId, Account> snapshot = Map.of(world.id(), world, fresh.id(), fresh);
                Transfer funding = Transfer.between(WorldAccount.ID, fresh.id(), openingBalanceMinor);
                if (!(Ledger.post(funding, snapshot) instanceof Ledger.Result.Posted(var updated))) {
                    throw new IllegalStateException("world funding should always post");
                }
                for (Account a : updated) {
                    accounts.put(a.id(), a);
                }
                long newWorld = balanceOf(updated, WorldAccount.ID);
                long newAccount = balanceOf(updated, fresh.id());
                recordPostings(funding.id(), WorldAccount.ID, fresh.id(), openingBalanceMinor, newWorld, newAccount, Instant.now());
                return accounts.get(fresh.id());
            });
        }
        creationOrder.add(fresh.id());
        return created;
    }

    @Override
    public Optional<Account> findAccount(AccountId id) {
        return Optional.ofNullable(accounts.get(id));
    }

    @Override
    public List<Account> listAccounts() {
        return creationOrder.stream().map(accounts::get).toList();
    }

    @Override
    public StatementPage statement(AccountId id, long beforePostingId, int limit) {
        List<StatementLine> lines = journal.getOrDefault(id, List.of());
        List<StatementLine> page = new ArrayList<>();
        boolean hasMore = false;
        // The list ascends by postingId, so walk it backwards for newest-first. One extra matching
        // row past the limit means there is another page.
        for (int i = lines.size() - 1; i >= 0; i--) {
            StatementLine line = lines.get(i);
            if (line.postingId() < beforePostingId) {
                if (page.size() == limit) {
                    hasMore = true;
                    break;
                }
                page.add(line);
            }
        }
        return new StatementPage(id, page, hasMore);
    }

    // Lock every account in UUID order so the audit sees a snapshot no transfer is mid-write on, and so
    // it takes its locks in the same global order transfers do and cannot deadlock one. Stored and derived
    // are written together under the account lock, so this store never drifts; the check still runs, both
    // to prove that and to sum the whole book, which must be zero.
    @Override
    public ReconciliationReport reconcile() {
        List<AccountId> ids = accounts.keySet().stream()
                .sorted(Comparator.comparing(AccountId::value))
                .toList();
        return locks.withAllLocked(ids, () -> {
            List<Drift> drifts = new ArrayList<>();
            long globalSum = 0;
            for (AccountId id : ids) {
                long stored = accounts.get(id).balanceMinor();
                long derived = journal.getOrDefault(id, List.of()).stream()
                        .mapToLong(StatementLine::amountMinor)
                        .sum();
                if (stored != derived) {
                    drifts.add(new Drift(id, stored, derived));
                }
                globalSum += stored;
            }
            return new ReconciliationReport(globalSum, ids.size(), drifts);
        });
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

    // Existence and identity are decided before any lock: they touch no account state.
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
        // Both account locks, lower-id-first, around the read-check-write. Disjoint transfers run
        // in parallel; the fixed order rules out a deadlock cycle.
        return locks.withBothLocked(request.from(), request.to(), () -> applyBalances(request));
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
                long newFrom = balanceOf(updated, request.from());
                long newTo = balanceOf(updated, request.to());
                Instant at = Instant.now();
                recordPostings(transfer.id(), request.from(), request.to(), request.amountMinor(), newFrom, newTo, at);
                yield new TransferOutcome.Applied(transfer.id(), newFrom, newTo, at);
            }
            case Ledger.Result.InsufficientFunds(var account, var balance, var requested) ->
                    new TransferOutcome.InsufficientFunds(account, balance, requested);
            // A mirrored two-posting transfer can never be unbalanced, so an Invalid here is a bug.
            case Ledger.Result.Invalid(var reason) ->
                    throw new IllegalStateException("mirrored transfer did not balance: " + reason);
        };
    }

    // Two postings per transfer, appended under the account locks so per-account postingIds ascend.
    private void recordPostings(TransferId transferId, AccountId from, AccountId to, long amount,
                                long newFrom, long newTo, Instant at) {
        long fromPostingId = postingSeq.getAndIncrement();
        long toPostingId = postingSeq.getAndIncrement();
        journalFor(from).add(new StatementLine(fromPostingId, transferId, to, -amount, newFrom, at));
        journalFor(to).add(new StatementLine(toPostingId, transferId, from, amount, newTo, at));
    }

    private List<StatementLine> journalFor(AccountId id) {
        return journal.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>());
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
