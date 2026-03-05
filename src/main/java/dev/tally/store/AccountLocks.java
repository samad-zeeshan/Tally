package dev.tally.store;

import dev.tally.core.AccountId;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * One lock per account, acquired lower-id-first so a deadlock cycle cannot form.
 *
 * Any consistent total order over the ids breaks Coffman's circular-wait condition; this uses
 * the natural order of the UUID. See ADR-0007.
 */
final class AccountLocks {
    private final ConcurrentHashMap<AccountId, ReentrantLock> locks = new ConcurrentHashMap<>();

    // Acquire both locks lower-id-first, run the action, release in reverse. The caller has
    // rejected same-account, so the two ids are distinct and the order is strict.
    <T> T withBothLocked(AccountId a, AccountId b, Supplier<T> action) {
        AccountId low = lower(a, b);
        AccountId high = low.equals(a) ? b : a;
        ReentrantLock first = lockFor(low);
        ReentrantLock second = lockFor(high);
        first.lock();
        try {
            second.lock();
            try {
                return action.get();
            } finally {
                second.unlock();
            }
        } finally {
            first.unlock();
        }
    }

    // Never removed, which is fine because accounts are never deleted, so the map is bounded by
    // the account count. The mapping function only builds a lock, so the short-computation rule holds.
    private ReentrantLock lockFor(AccountId id) {
        return locks.computeIfAbsent(id, k -> new ReentrantLock());
    }

    // "Lower" is UUID.compareTo. The point is that the order is total and global, not that it is
    // numeric: compareTo is signed, so the nil world UUID is not even the least element.
    static AccountId lower(AccountId x, AccountId y) {
        return x.value().compareTo(y.value()) <= 0 ? x : y;
    }
}
