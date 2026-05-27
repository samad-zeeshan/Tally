package dev.tally.fraud;

import dev.tally.core.AccountId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Scores in maps, for the in-memory ledger. putIfAbsent on the posting id plays the primary key's part.
 */
public final class InMemoryScoreStore implements ScoreStore {
    private final Map<Long, Score> byPosting = new ConcurrentHashMap<>();
    // Per account, keyed by posting id, so "below this posting, newest first" is a descending head map.
    private final Map<AccountId, NavigableMap<Long, Score>> byPayer = new ConcurrentHashMap<>();
    private final Map<AccountId, NavigableMap<Long, Score>> byPayee = new ConcurrentHashMap<>();

    @Override
    public boolean insertIfAbsent(Score score) {
        if (byPosting.putIfAbsent(score.postingId(), score) != null) {
            return false;
        }
        byPayer.computeIfAbsent(score.account(), k -> new ConcurrentSkipListMap<>()).put(score.postingId(), score);
        byPayee.computeIfAbsent(score.counterparty(), k -> new ConcurrentSkipListMap<>()).put(score.postingId(), score);
        return true;
    }

    @Override
    public boolean contains(long postingId) {
        return byPosting.containsKey(postingId);
    }

    @Override
    public List<Score> outgoingBefore(AccountId account, long beforePostingId, int limit) {
        NavigableMap<Long, Score> scores = byPayer.get(account);
        if (scores == null) {
            return List.of();
        }
        return scores.headMap(beforePostingId, false).descendingMap().values().stream().limit(limit).toList();
    }

    @Override
    public List<Score> incomingSince(AccountId account, Instant since, long beforePostingId) {
        NavigableMap<Long, Score> scores = byPayee.get(account);
        if (scores == null) {
            return List.of();
        }
        List<Score> out = new ArrayList<>();
        for (Score s : scores.headMap(beforePostingId, false).descendingMap().values()) {
            if (!s.eventAt().isBefore(since)) {
                out.add(s);
            }
        }
        out.sort(Comparator.comparingLong(Score::postingId).reversed());
        return out;
    }
}
