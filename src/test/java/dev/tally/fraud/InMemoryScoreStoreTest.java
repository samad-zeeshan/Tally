package dev.tally.fraud;

import dev.tally.store.InMemoryStore;
import dev.tally.store.Store;

class InMemoryScoreStoreTest extends ScoreStoreContractTest {
    @Override
    protected Store ledger() {
        return new InMemoryStore();
    }

    @Override
    protected ScoreStore scores() {
        return new InMemoryScoreStore();
    }
}
