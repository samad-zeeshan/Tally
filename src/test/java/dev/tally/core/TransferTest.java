package dev.tally.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransferTest {

    private final AccountId a = AccountId.newId();
    private final AccountId b = AccountId.newId();

    @Test
    void betweenBuildsDebitAndCreditPair() {
        Transfer t = Transfer.between(a, b, 250);
        assertEquals(List.of(new Posting(a, -250), new Posting(b, 250)), t.postings());
    }

    @Test
    void betweenRejectsZeroAmount() {
        assertThrows(IllegalArgumentException.class, () -> Transfer.between(a, b, 0));
    }

    @Test
    void betweenRejectsNegativeAmount() {
        assertThrows(IllegalArgumentException.class, () -> Transfer.between(a, b, -5));
    }

    @Test
    void constructorRejectsEmptyPostings() {
        TransferId id = TransferId.newId();
        assertThrows(IllegalArgumentException.class, () -> new Transfer(id, List.of()));
    }

    @Test
    void postingsListIsImmutable() {
        List<Posting> source = new ArrayList<>();
        source.add(new Posting(a, -100));
        source.add(new Posting(b, 100));
        Transfer t = new Transfer(TransferId.newId(), source);

        source.clear();   // mutating the source must not touch the transfer
        assertEquals(2, t.postings().size());
        assertThrows(UnsupportedOperationException.class, () -> t.postings().add(new Posting(a, 1)));
    }
}
