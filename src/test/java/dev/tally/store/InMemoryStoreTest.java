package dev.tally.store;

/**
 * The in-memory store must pass the same contract as the Postgres store.
 */
class InMemoryStoreTest extends StoreContractTest {
    @Override
    protected Store newStore() {
        return new InMemoryStore();
    }
}
