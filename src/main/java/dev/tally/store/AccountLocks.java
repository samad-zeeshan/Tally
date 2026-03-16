package dev.tally.store;

import dev.tally.core.AccountId;

import java.util.ArrayList;
import java.util.List;
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

    // Lock every account, ids given already in UUID order, so the audit takes its locks in the same
    // global order transfers use and cannot deadlock one. Release in reverse.
    <T> T withAllLocked(List<AccountId> idsInOrder, Supplier<T> action) {
        List<ReentrantLock> acquired = new ArrayList<>(idsInOrder.size());
        try {
            for (AccountId id : idsInOrder) {
                ReentrantLock lock = lockFor(id);
                lock.lock();
                acquired.add(lock);
            }
            return action.get();
        } finally {
            for (int i = acquired.size() - 1; i >= 0; i--) {
                acquired.get(i).unlock();
            }
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
