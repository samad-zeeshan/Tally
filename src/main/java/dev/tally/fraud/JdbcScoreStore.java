package dev.tally.fraud;

import dev.tally.core.AccountId;
import dev.tally.core.TransferId;
import dev.tally.db.Pool;
import dev.tally.store.StoreException;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Scores in the posting_scores table (migrations 002 and 003). Each call borrows one connection from the shared
 * pool and returns it, and the scorer calls from a single thread, so it holds at most one at a time.
 */
public final class JdbcScoreStore implements ScoreStore {
    private static final String COLUMNS =
            "posting_id, transfer_id, account_id, counterparty_id, amount_minor, score, fired_rules, event_at, scored_at, explanation";
    // What the scorer reads before every score. The explanation is left out: no rule reads it, and on a
    // busy account parsing it for hundreds of rows held a pooled connection long enough to starve transfers.
    private static final String WINDOW_COLUMNS =
            "posting_id, transfer_id, account_id, counterparty_id, amount_minor, score, fired_rules, event_at, scored_at, NULL AS explanation";

    private final Pool pool;

    public JdbcScoreStore(Pool pool) {
        this.pool = pool;
    }

    // ON CONFLICT DO NOTHING makes a second delivery a no-op even when two replicas race on one posting,
    // which a contains() check before the insert could not promise.
    @Override
    public boolean insertIfAbsent(Score score) {
        Connection conn = pool.borrow();
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO posting_scores (" + COLUMNS + ") "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (posting_id) DO NOTHING")) {
            ps.setLong(1, score.postingId());
            ps.setObject(2, score.transferId().value());
            ps.setObject(3, score.account().value());
            ps.setObject(4, score.counterparty().value());
            ps.setLong(5, score.amountMinor());
            ps.setInt(6, score.score());
            ps.setArray(7, conn.createArrayOf("text", score.rules().toArray()));
            ps.setObject(8, OffsetDateTime.ofInstant(score.eventAt(), ZoneOffset.UTC));
            ps.setObject(9, OffsetDateTime.ofInstant(score.scoredAt(), ZoneOffset.UTC));
            ps.setString(10, dev.tally.json.Json.write(score.explanation().toJson()));
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new StoreException("insert score failed", e);
        } finally {
            pool.giveBack(conn);
        }
    }

    @Override
    public boolean contains(long postingId) {
        Connection conn = pool.borrow();
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM posting_scores WHERE posting_id = ?")) {
            ps.setLong(1, postingId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new StoreException("score lookup failed", e);
        } finally {
            pool.giveBack(conn);
        }
    }

    @Override
    public List<Score> outgoingBefore(AccountId account, long beforePostingId, int limit) {
        return query("SELECT " + WINDOW_COLUMNS + " FROM posting_scores WHERE account_id = ? AND posting_id < ? "
                + "ORDER BY posting_id DESC LIMIT ?", ps -> {
            ps.setObject(1, account.value());
            ps.setLong(2, beforePostingId);
            ps.setInt(3, limit);
        });
    }

    @Override
    public List<Score> incomingSince(AccountId account, Instant since, long beforePostingId) {
        return query("SELECT " + WINDOW_COLUMNS + " FROM posting_scores WHERE counterparty_id = ? AND event_at >= ? "
                + "AND posting_id < ? ORDER BY posting_id DESC", ps -> {
            ps.setObject(1, account.value());
            ps.setObject(2, OffsetDateTime.ofInstant(since, ZoneOffset.UTC));
            ps.setLong(3, beforePostingId);
        });
    }

    @Override
    public List<Score> recent(AccountId account, int limit) {
        return query("SELECT " + COLUMNS + " FROM posting_scores WHERE account_id = ? ORDER BY posting_id DESC LIMIT ?", ps -> {
            ps.setObject(1, account.value());
            ps.setInt(2, limit);
        });
    }

    // The inner LIMIT stops the scan at the cap, so a payee with a million payers costs the same as one with fifty.
    @Override
    public int distinctPayersBefore(AccountId account, long beforePostingId, int cap) {
        return count("SELECT count(*) FROM (SELECT DISTINCT account_id FROM posting_scores "
                + "WHERE counterparty_id = ? AND posting_id < ? LIMIT ?) payers", account, beforePostingId, cap);
    }

    @Override
    public int outgoingCountBefore(AccountId account, long beforePostingId, int cap) {
        return count("SELECT count(*) FROM (SELECT 1 FROM posting_scores WHERE account_id = ? AND posting_id < ? LIMIT ?) sent",
                account, beforePostingId, cap);
    }

    @Override
    public List<Score> outgoingSince(AccountId account, Instant since, long beforePostingId, int limit) {
        return query("SELECT " + WINDOW_COLUMNS + " FROM posting_scores WHERE account_id = ? AND event_at >= ? "
                + "AND posting_id < ? ORDER BY posting_id DESC LIMIT ?", ps -> {
            ps.setObject(1, account.value());
            ps.setObject(2, OffsetDateTime.ofInstant(since, ZoneOffset.UTC));
            ps.setLong(3, beforePostingId);
            ps.setInt(4, limit);
        });
    }

    private int count(String sql, AccountId account, long beforePostingId, int cap) {
        Connection conn = pool.borrow();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, account.value());
            ps.setLong(2, beforePostingId);
            ps.setInt(3, cap);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new StoreException("score count failed", e);
        } finally {
            pool.giveBack(conn);
        }
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private List<Score> query(String sql, Binder binder) {
        Connection conn = pool.borrow();
        List<Score> scores = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    scores.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new StoreException("score query failed", e);
        } finally {
            pool.giveBack(conn);
        }
        return scores;
    }

    private static Score read(ResultSet rs) throws SQLException {
        Array rules = rs.getArray("fired_rules");
        return new Score(
                rs.getLong("posting_id"),
                new TransferId(rs.getObject("transfer_id", UUID.class)),
                new AccountId(rs.getObject("account_id", UUID.class)),
                new AccountId(rs.getObject("counterparty_id", UUID.class)),
                rs.getLong("amount_minor"),
                rs.getInt("score"),
                List.of((String[]) rules.getArray()),
                rs.getObject("event_at", OffsetDateTime.class).toInstant(),
                rs.getObject("scored_at", OffsetDateTime.class).toInstant(),
                Explanation.fromJson(rs.getString("explanation")));
    }
}
