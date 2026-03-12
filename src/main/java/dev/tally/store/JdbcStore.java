package dev.tally.store;

import dev.tally.core.Account;
import dev.tally.core.AccountId;
import dev.tally.core.StatementLine;
import dev.tally.core.StatementPage;
import dev.tally.core.TransferId;
import dev.tally.core.TransferOutcome;
import dev.tally.core.TransferRequest;
import dev.tally.db.Pool;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The Postgres store: one transaction per transfer, row locks in UUID order, and a unique constraint
 * on the idempotency key that holds across restarts. Same seam as the in-memory store.
 */
public final class JdbcStore implements Store {
    private static final UUID WORLD = new UUID(0L, 0L);

    private final Pool pool;
    private final Runnable midTransferFault;   // tests only, runs between the debit and the credit

    public JdbcStore(Pool pool) {
        this(pool, () -> {});
    }

    JdbcStore(Pool pool, Runnable midTransferFault) {
        this.pool = pool;
        this.midTransferFault = midTransferFault;
    }

    // The account row and its world opening are one transaction, so an account is visible only once
    // fully funded and a crash between the two writes leaves nothing.
    @Override
    public Account createAccount(String name, long openingBalanceMinor) {
        if (openingBalanceMinor < 0) {
            throw new IllegalArgumentException("opening balance must not be negative: " + openingBalanceMinor);
        }
        UUID id = UUID.randomUUID();
        Connection conn = pool.borrow();
        try {
            conn.setAutoCommit(false);
            Instant createdAt;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO accounts (id, name, allow_negative, balance_minor) VALUES (?, ?, false, ?) RETURNING created_at")) {
                ps.setObject(1, id);
                ps.setString(2, name);
                ps.setLong(3, openingBalanceMinor);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    createdAt = rs.getObject(1, OffsetDateTime.class).toInstant();
                }
            }
            if (openingBalanceMinor > 0) {
                fundFromWorld(conn, id, openingBalanceMinor);
            }
            conn.commit();
            return new Account(new AccountId(id), name, openingBalanceMinor, false, createdAt);
        } catch (SQLException e) {
            throw new StoreException("createAccount failed", e);
        } finally {
            pool.giveBack(conn);   // rolls back a failed transaction and resets autocommit
        }
    }

    private void fundFromWorld(Connection conn, UUID accountId, long opening) throws SQLException {
        UUID transferId = UUID.randomUUID();
        // The opening carries the system key open:<id>, which holds a colon the wire regex forbids, but
        // it is internal and never validated at the edge; it is unique because the account id is fresh.
        String key = "open:" + accountId;
        String fingerprint = Fingerprint.of(new TransferRequest(key, new AccountId(WORLD), new AccountId(accountId), opening));
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO transfers (id, idempotency_key, request_fingerprint, from_account_id, to_account_id, amount_minor, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'applied')")) {
            ps.setObject(1, transferId);
            ps.setString(2, key);
            ps.setString(3, fingerprint);
            ps.setObject(4, WORLD);
            ps.setObject(5, accountId);
            ps.setLong(6, opening);
            ps.executeUpdate();
        }
        // The UPDATE takes world's row lock, so concurrent openings serialize on world with no explicit
        // FOR UPDATE. World is allow_negative, so the balance CHECK does not fire.
        long worldAfter;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE accounts SET balance_minor = balance_minor - ? WHERE id = ? RETURNING balance_minor")) {
            ps.setLong(1, opening);
            ps.setObject(2, WORLD);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                worldAfter = rs.getLong(1);
            }
        }
        insertPostings(conn, transferId, WORLD, -opening, worldAfter, accountId, opening, opening);
    }

    @Override
    public Optional<Account> findAccount(AccountId id) {
        Connection conn = pool.borrow();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, allow_negative, balance_minor, created_at FROM accounts WHERE id = ?")) {
            ps.setObject(1, id.value());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(readAccount(rs));
            }
        } catch (SQLException e) {
            throw new StoreException("findAccount failed", e);
        } finally {
            pool.giveBack(conn);
        }
    }

    @Override
    public StatementPage statement(AccountId id, long beforePostingId, int limit) {
        Connection conn = pool.borrow();
        List<StatementLine> lines = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT p.id, p.transfer_id, cp.account_id AS counterparty, p.amount_minor, p.balance_after_minor, p.created_at "
                        + "FROM postings p JOIN postings cp ON cp.transfer_id = p.transfer_id AND cp.account_id <> p.account_id "
                        + "WHERE p.account_id = ? AND p.id < ? ORDER BY p.id DESC LIMIT ?")) {
            ps.setObject(1, id.value());
            ps.setLong(2, beforePostingId);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lines.add(new StatementLine(
                            rs.getLong("id"),
                            new TransferId(rs.getObject("transfer_id", UUID.class)),
                            new AccountId(rs.getObject("counterparty", UUID.class)),
                            rs.getLong("amount_minor"),
                            rs.getLong("balance_after_minor"),
                            rs.getObject("created_at", OffsetDateTime.class).toInstant()));
                }
            }
        } catch (SQLException e) {
            throw new StoreException("statement failed", e);
        } finally {
            pool.giveBack(conn);
        }
        return new StatementPage(id, lines);
    }

    @Override
    public TransferOutcome apply(TransferRequest request) {
        if (request.from().equals(request.to())) {
            throw new IllegalArgumentException("same account");
        }
        if (request.amountMinor() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        UUID from = request.from().value();
        UUID to = request.to().value();
        // Reserved is caught before any database work, so it writes nothing and never consumes the key.
        if (WORLD.equals(from) || WORLD.equals(to)) {
            return new TransferOutcome.ReservedAccount(WORLD.equals(from) ? request.from() : request.to());
        }
        String fingerprint = Fingerprint.of(request);
        Connection conn = pool.borrow();
        try {
            // The fast path is an optimization; the unique constraint below is the truth.
            TransferOutcome fast = fastPathReplay(conn, request, fingerprint);
            if (fast != null) {
                return fast;
            }
            return applyInTransaction(conn, request, from, to, fingerprint);
        } catch (SQLException e) {
            throw new StoreException("apply failed", e);
        } catch (RuntimeException e) {
            throw (e instanceof StoreException se) ? se : new StoreException("apply failed", e);
        } finally {
            pool.giveBack(conn);   // rolls back a failed transaction; a mid-transfer fault leaves nothing
        }
    }

    private TransferOutcome fastPathReplay(Connection conn, TransferRequest request, String fingerprint) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, request_fingerprint, amount_minor, status FROM transfers WHERE idempotency_key = ?")) {
            ps.setString(1, request.idempotencyKey());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? reconstruct(conn, rs, request, fingerprint) : null;
            }
        }
    }

    private TransferOutcome applyInTransaction(Connection conn, TransferRequest request, UUID from, UUID to,
                                               String fingerprint) throws SQLException {
        conn.setAutoCommit(false);
        // Lock the two account rows, lower UUID first, one single-row statement each. Two statements make
        // the lock order a contract, not a query-plan detail. This is the Stage 2 deadlock argument in SQL.
        UUID lower = from.compareTo(to) <= 0 ? from : to;
        UUID higher = lower.equals(from) ? to : from;
        Long lowerBalance = lockBalance(conn, lower);
        Long higherBalance = lockBalance(conn, higher);
        if (lowerBalance == null || higherBalance == null) {
            // An unknown account is caught before the transfers insert, so it writes nothing and the key stays free.
            conn.rollback();
            return new TransferOutcome.UnknownAccount(new AccountId(lowerBalance == null ? lower : higher));
        }
        long fromBalance = from.equals(lower) ? lowerBalance : higherBalance;
        long toBalance = to.equals(lower) ? lowerBalance : higherBalance;
        long amount = request.amountMinor();
        String status = fromBalance >= amount ? "applied" : "insufficient_funds";

        UUID transferId = UUID.randomUUID();
        Instant createdAt;
        // ON CONFLICT DO NOTHING is the arbiter: two same-key requests that both miss the fast path both
        // reach here, Postgres blocks the second on the first's speculative insert, then returns zero rows
        // and the loser replays the committed row, even if the loser had computed a different status.
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO transfers (id, idempotency_key, request_fingerprint, from_account_id, to_account_id, amount_minor, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (idempotency_key) DO NOTHING RETURNING created_at")) {
            ps.setObject(1, transferId);
            ps.setString(2, request.idempotencyKey());
            ps.setString(3, fingerprint);
            ps.setObject(4, from);
            ps.setObject(5, to);
            ps.setLong(6, amount);
            ps.setString(7, status);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    conn.rollback();
                    return replayFromCommitted(conn, request, fingerprint);
                }
                createdAt = rs.getObject(1, OffsetDateTime.class).toInstant();
            }
        }
        if (status.equals("insufficient_funds")) {
            // The transfers row is written and no postings; the key is consumed and replays deterministically.
            conn.commit();
            return new TransferOutcome.InsufficientFunds(request.from(), fromBalance, amount);
        }
        long fromAfter = fromBalance - amount;
        long toAfter = toBalance + amount;
        updateBalance(conn, from, fromAfter);
        midTransferFault.run();   // the widest half-applied window, between the two balance updates
        updateBalance(conn, to, toAfter);
        insertPostings(conn, transferId, from, -amount, fromAfter, to, amount, toAfter);
        conn.commit();
        return new TransferOutcome.Applied(new TransferId(transferId), fromAfter, toAfter, createdAt);
    }

    private TransferOutcome replayFromCommitted(Connection conn, TransferRequest request, String fingerprint) throws SQLException {
        conn.setAutoCommit(true);
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, request_fingerprint, amount_minor, status FROM transfers WHERE idempotency_key = ?")) {
            ps.setString(1, request.idempotencyKey());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new StoreException("idempotency key vanished after ON CONFLICT");
                }
                return reconstruct(conn, rs, request, fingerprint);
            }
        }
    }

    // Rebuild the stored outcome, wrapped in Replayed. A fingerprint mismatch is a key reused with a
    // different request, which is a KeyConflict, not a replay.
    private TransferOutcome reconstruct(Connection conn, ResultSet transferRow, TransferRequest request,
                                        String fingerprint) throws SQLException {
        if (!transferRow.getString("request_fingerprint").equals(fingerprint)) {
            return new TransferOutcome.KeyConflict(request.idempotencyKey());
        }
        if (transferRow.getString("status").equals("insufficient_funds")) {
            long fromBalance = currentBalance(conn, request.from().value());
            return new TransferOutcome.Replayed(
                    new TransferOutcome.InsufficientFunds(request.from(), fromBalance, transferRow.getLong("amount_minor")));
        }
        UUID transferId = transferRow.getObject("id", UUID.class);
        long fromAfter = 0;
        long toAfter = 0;
        Instant at = null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT p.account_id, p.balance_after_minor, t.created_at "
                        + "FROM postings p JOIN transfers t ON t.id = p.transfer_id WHERE p.transfer_id = ?")) {
            ps.setObject(1, transferId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID account = rs.getObject("account_id", UUID.class);
                    at = rs.getObject("created_at", OffsetDateTime.class).toInstant();
                    if (account.equals(request.from().value())) {
                        fromAfter = rs.getLong("balance_after_minor");
                    } else if (account.equals(request.to().value())) {
                        toAfter = rs.getLong("balance_after_minor");
                    }
                }
            }
        }
        return new TransferOutcome.Replayed(new TransferOutcome.Applied(new TransferId(transferId), fromAfter, toAfter, at));
    }

    private Long lockBalance(Connection conn, UUID id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT balance_minor FROM accounts WHERE id = ? FOR UPDATE")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private long currentBalance(Connection conn, UUID id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT balance_minor FROM accounts WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void updateBalance(Connection conn, UUID id, long balance) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE accounts SET balance_minor = ? WHERE id = ?")) {
            ps.setLong(1, balance);
            ps.setObject(2, id);
            ps.executeUpdate();
        }
    }

    private void insertPostings(Connection conn, UUID transferId, UUID fromId, long fromAmount, long fromAfter,
                                UUID toId, long toAmount, long toAfter) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO postings (transfer_id, account_id, amount_minor, balance_after_minor) "
                        + "VALUES (?, ?, ?, ?), (?, ?, ?, ?)")) {
            ps.setObject(1, transferId);
            ps.setObject(2, fromId);
            ps.setLong(3, fromAmount);
            ps.setLong(4, fromAfter);
            ps.setObject(5, transferId);
            ps.setObject(6, toId);
            ps.setLong(7, toAmount);
            ps.setLong(8, toAfter);
            ps.executeUpdate();
        }
    }

    private static Account readAccount(ResultSet rs) throws SQLException {
        return new Account(
                new AccountId(rs.getObject("id", UUID.class)),
                rs.getString("name"),
                rs.getLong("balance_minor"),
                rs.getBoolean("allow_negative"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
