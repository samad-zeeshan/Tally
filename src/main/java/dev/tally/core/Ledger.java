package dev.tally.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole balancing rule, pure and stateless.
 *
 * It takes a transfer and a snapshot of the accounts it touches and returns one of three
 * results. The store owns state and calls this for the arithmetic.
 */
public final class Ledger {
    private Ledger() {}

    public sealed interface Result {
        record Posted(List<Account> updated) implements Result {}
        record InsufficientFunds(AccountId account, long balanceMinor, long requestedMinor) implements Result {}
        record Invalid(RejectReason reason) implements Result {}
    }

    public static Result post(Transfer transfer, Map<AccountId, Account> current) {
        // Sum to zero with an exact fold. A wrapped sum is how a naive ledger prints money:
        // MAX + MAX + 2 wraps to 0, so a naive + would accept an unbalanced transfer.
        long sum = 0;
        try {
            for (Posting p : transfer.postings()) {
                sum = Math.addExact(sum, p.amountMinor());
            }
        } catch (ArithmeticException overflow) {
            return new Result.Invalid(RejectReason.OVERFLOW);
        }
        if (sum != 0L) {
            return new Result.Invalid(RejectReason.UNBALANCED);
        }
        // The fold is left to right and exact on purpose: a technically balanced transfer whose
        // intermediate sum overflows is rejected too. Legs near Long.MAX_VALUE could never apply
        // to a real balance anyway, and the exact fold keeps the arithmetic honest.

        // Net the postings by account in first-appearance order, so the balance check is deterministic.
        Map<AccountId, Long> deltas = new LinkedHashMap<>();
        try {
            for (Posting p : transfer.postings()) {
                deltas.merge(p.accountId(), p.amountMinor(), Math::addExact);
            }
        } catch (ArithmeticException overflow) {
            return new Result.Invalid(RejectReason.OVERFLOW);
        }

        List<Account> updated = new ArrayList<>();
        for (Map.Entry<AccountId, Long> entry : deltas.entrySet()) {
            Account account = current.get(entry.getKey());
            if (account == null) {
                // The store resolves accounts before calling post, so a missing one here is a bug.
                throw new IllegalStateException("account not in snapshot: " + entry.getKey());
            }
            long delta = entry.getValue();
            long newBalance;
            try {
                newBalance = Math.addExact(account.balanceMinor(), delta);
            } catch (ArithmeticException overflow) {
                return new Result.Invalid(RejectReason.OVERFLOW);
            }
            // allowNegative exempts exactly one account, world, from the non-negative rule.
            if (newBalance < 0 && !account.allowNegative()) {
                return new Result.InsufficientFunds(account.id(), account.balanceMinor(), -delta);
            }
            updated.add(new Account(account.id(), account.name(), newBalance,
                    account.allowNegative(), account.createdAt()));
        }
        return new Result.Posted(updated);
    }
}
