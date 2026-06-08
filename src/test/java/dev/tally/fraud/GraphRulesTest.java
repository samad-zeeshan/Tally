package dev.tally.fraud;

import dev.tally.core.AccountId;
import dev.tally.core.TransferId;
import dev.tally.obs.Metrics;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The relational rules, driven through the real scorer so the graph reads come from the store.
 */
class GraphRulesTest {
    private static final Instant NOON = Instant.parse("2026-03-10T12:00:00Z");

    private final InMemoryScoreStore store = new InMemoryScoreStore();
    private final Scorer scorer = new Scorer(store, Rules.V2, new Metrics());
    private long nextPosting = 1;

    private Score pay(AccountId from, AccountId to, long amount, Instant at) {
        long debit = nextPosting++;
        return scorer.score(new PostingEvent(debit, nextPosting++, TransferId.newId(), from, to, amount, at)).orElseThrow();
    }

    @Test
    void aBurstToAnAccountNobodyHasPaidIsFlagged() {
        AccountId me = AccountId.newId();
        AccountId fresh = AccountId.newId();
        pay(me, fresh, 3_000, NOON);
        pay(me, fresh, 3_000, NOON.plusSeconds(40));
        Score third = pay(me, fresh, 3_000, NOON.plusSeconds(80));
        assertTrue(third.rules().contains("fresh_payee_burst"), third.rules().toString());
        assertEquals(30, third.explanation().points().get("fresh_payee_burst"));
    }

    @Test
    void aBurstToAShopManyPeoplePayIsNotABurstToAFreshPayee() {
        AccountId shop = AccountId.newId();
        for (int i = 0; i < 3; i++) {
            pay(AccountId.newId(), shop, 1_000, NOON.minus(Duration.ofDays(1)).plusSeconds(i));
        }
        AccountId me = AccountId.newId();
        pay(me, shop, 3_000, NOON);
        pay(me, shop, 3_000, NOON.plusSeconds(40));
        Score third = pay(me, shop, 3_000, NOON.plusSeconds(80));
        assertFalse(third.rules().contains("fresh_payee_burst"));
        assertEquals(-20, third.explanation().points().get("established_payee"), "and the established payee discounts it");
    }

    @Test
    void forwardingMostOfAFlaggedPaymentFiresAndNamesThatPayment() {
        AccountId victim = AccountId.newId();
        AccountId mule = AccountId.newId();
        AccountId next = AccountId.newId();
        // Round and large with a new payee and history: enough rules to cross the flag line.
        for (int i = 0; i < 5; i++) {
            pay(victim, AccountId.newId(), 1_000, NOON.minus(Duration.ofDays(5 - i)));
        }
        Score seed = pay(victim, mule, 500_000, NOON);
        assertTrue(seed.flagged(), "the victim's payment is flagged: " + seed.rules());
        Score forward = pay(mule, next, 470_000, NOON.plus(Duration.ofMinutes(8)));
        assertEquals(40, forward.explanation().points().get("forwards_flagged"));
        assertEquals(1L, forward.explanation().features().get("seed_hops"));
        assertEquals(500_000L, forward.explanation().features().get("flagged_in_60m_minor"));
        assertTrue(forward.explanation().evidence().contains(seed.postingId()), "the alert names the posting it rests on");
    }

    @Test
    void moneyThatComesBackWithinADayIsACycle() {
        AccountId a = AccountId.newId();
        AccountId b = AccountId.newId();
        AccountId c = AccountId.newId();
        Score ab = pay(a, b, 10_000, NOON);
        Score bc = pay(b, c, 9_000, NOON.plus(Duration.ofHours(1)));
        Score ca = pay(c, a, 8_000, NOON.plus(Duration.ofHours(2)));
        // a pays b again: b reaches a through c, so this payment closes a loop.
        Score again = pay(a, b, 7_000, NOON.plus(Duration.ofHours(3)));
        assertEquals(1L, again.explanation().features().get("cycle_24h"));
        assertEquals(25, again.explanation().points().get("cycle_24h"));
        assertEquals(List.of(bc.postingId(), ca.postingId()), again.explanation().evidence().stream()
                .filter(id -> id != ab.postingId()).toList());
    }

    @Test
    void everyScoreCarriesEveryFeatureItCouldHaveUsed() {
        Score s = pay(AccountId.newId(), AccountId.newId(), 1_000, NOON);
        for (FeatureRule.Feature f : FeatureRule.Feature.values()) {
            assertTrue(s.explanation().features().containsKey(f.wireName), f.wireName);
        }
    }
}
