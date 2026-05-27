package dev.tally.fraud;

import dev.tally.obs.Logs;
import dev.tally.obs.Metrics;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A bounded queue with one consumer thread. offer never waits: a full queue drops the event and counts
 * it, because a transfer must not slow down for an advisory score.
 */
final class ScoringQueue implements AutoCloseable {
    private static final Logger LOG = Logs.get(ScoringQueue.class);

    private final BlockingQueue<PostingEvent> queue;
    private final Consumer<PostingEvent> handler;
    private final Metrics metrics;
    private final Thread consumer;
    // Accepted but not yet finished, including the one being handled. The queue's own size misses that
    // one, so awaitIdle would report idle while an event is still being scored.
    private final AtomicLong pending = new AtomicLong();
    private volatile boolean closed;

    ScoringQueue(int capacity, Consumer<PostingEvent> handler, Metrics metrics) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.handler = handler;
        this.metrics = metrics;
        this.consumer = Thread.ofVirtual().name("fraud-scorer").start(this::drain);
    }

    boolean offer(PostingEvent event) {
        pending.incrementAndGet();
        if (closed || !queue.offer(event)) {
            pending.decrementAndGet();
            metrics.fraudPostings.inc("dropped");
            return false;
        }
        return true;
    }

    int depth() {
        return queue.size();
    }

    boolean awaitIdle(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (pending.get() > 0) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private void drain() {
        while (!closed) {
            PostingEvent event;
            try {
                event = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            try {
                handler.accept(event);
            } catch (RuntimeException e) {
                // A scorer bug or a database error costs this one score, never the consumer thread.
                metrics.fraudPostings.inc("failed");
                LOG.log(Level.WARNING, "scoring failed posting=" + event.debitPostingId(), e);
            } finally {
                pending.decrementAndGet();
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        consumer.interrupt();
        try {
            consumer.join(Duration.ofSeconds(2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
