package dev.tally.store;

import dev.tally.core.AccountId;
import dev.tally.core.TransferOutcome;
import dev.tally.core.TransferRequest;
import dev.tally.core.WorldAccount;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ConservationTest {

    @Test
    void totalMoneyIsConservedAcrossRandomBatch() {
        InMemoryStore store = new InMemoryStore();
        List<AccountId> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ids.add(store.createAccount("acct-" + i, 5_000).id());
        }

        // Fixed seed so a failure reproduces exactly; this stands in for a property-testing library.
        Random random = new Random(42);
        int applied = 0;
        int rejected = 0;
        for (int i = 0; i < 10_000; i++) {
            int from = random.nextInt(10);
            int to = random.nextInt(10);
            long amount = random.nextLong(1, 2_001);
            // A same-account transfer is a client error the store rejects up front, so skip the draw.
            if (from == to) {
                continue;
            }
            TransferRequest request = new TransferRequest("batch-" + i, ids.get(from), ids.get(to), amount);
            switch (store.apply(request)) {
                case TransferOutcome.Applied _ -> applied++;
                case TransferOutcome.InsufficientFunds _ -> rejected++;
                case TransferOutcome.UnknownAccount _ -> fail("no unknown account in this batch");
                case TransferOutcome.ReservedAccount _ -> fail("the batch never names world");
                case TransferOutcome.KeyConflict _ -> fail("every key is unique");
                case TransferOutcome.Replayed _ -> fail("every key is unique");
            }
        }

        long userTotal = 0;
        for (AccountId id : ids) {
            long bal = store.findAccount(id).orElseThrow().balanceMinor();
            assertTrue(bal >= 0, "an ordinary balance went negative");
            userTotal += bal;
        }
        long world = store.findAccount(WorldAccount.ID).orElseThrow().balanceMinor();

        assertEquals(50_000, userTotal, "transfers conserve the user side");
        assertEquals(0, userTotal + world, "the whole book sums to zero");
        assertTrue(world <= 0, "world is never positive");
        // Both counts positive proves the batch exercised both paths; a batch that never rejects tests nothing.
        assertTrue(applied > 0, "the batch applied some transfers");
        assertTrue(rejected > 0, "the batch rejected some overdrafts");
    }
}
