package dev.tally.fraud;

import dev.tally.core.AccountId;
import dev.tally.core.TransferId;
import dev.tally.obs.Metrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The queue between the transfer path and the scorer: publishing never waits, a full queue drops and
 * counts, and a failing scorer does not stop the consumer.
 */
class ScoringQueueTest {
    private final Metrics metrics = new Metrics();
    private long nextPosting = 1;

    private PostingEvent event() {
        long debit = nextPosting++;
        return new PostingEvent(debit, nextPosting++, TransferId.newId(), AccountId.newId(), AccountId.newId(),
                1_000, Instant.parse("2026-03-10T12:00:00Z"));
    }

    @Test
    @Timeout(5)
    void publishingReturnsAtOnceWhileTheConsumerIsStuck() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (ScoringQueue queue = new ScoringQueue(100, e -> await(release), metrics)) {
            long start = System.nanoTime();
            for (int i = 0; i < 50; i++) {
                assertTrue(queue.offer(event()));
            }
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMillis < 1_000, "50 offers took " + elapsedMillis + " ms against a stuck consumer");
            release.countDown();
            assertTrue(queue.awaitIdle(Duration.ofSeconds(3)));
        }
    }

    @Test
    @Timeout(5)
    void aFullQueueDropsTheEventAndCountsIt() {
        CountDownLatch release = new CountDownLatch(1);
        try (ScoringQueue queue = new ScoringQueue(2, e -> await(release), metrics)) {
            // One event is taken by the stuck consumer and two fill the queue, so the fourth has no room.
            int accepted = 0;
            for (int i = 0; i < 4; i++) {
                if (queue.offer(event())) {
                    accepted++;
                }
            }
            assertTrue(accepted <= 3, "accepted " + accepted);
            assertTrue(metrics.fraudPostings.value("dropped") >= 1);
            release.countDown();
        }
    }

    @Test
    @Timeout(5)
    void aScorerThatThrowsDoesNotStopTheConsumer() {
        List<Long> handled = new CopyOnWriteArrayList<>();
        try (ScoringQueue queue = new ScoringQueue(10, e -> {
            if (e.debitPostingId() == 1) {
                throw new IllegalStateException("scorer bug");
            }
            handled.add(e.debitPostingId());
        }, metrics)) {
            queue.offer(event());
            queue.offer(event());
            assertTrue(queue.awaitIdle(Duration.ofSeconds(3)));
        }
        assertEquals(List.of(3L), handled);
        assertEquals(1, metrics.fraudPostings.value("failed"));
    }

    @Test
    void aClosedQueueAcceptsNothing() {
        ScoringQueue queue = new ScoringQueue(10, e -> {}, metrics);
        queue.close();
        assertFalse(queue.offer(event()));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
