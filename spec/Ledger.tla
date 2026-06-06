------------------------------- MODULE Ledger -------------------------------
(* The transfer and retry protocol of JdbcStore.apply, with lost messages, crashes and early retries.

   Each worker step is one statement of the Postgres transaction, and a crash anywhere rolls it back. *)
EXTENDS Integers, Sequences, FiniteSets, TLC

CONSTANTS
    World, Accounts,
    Clients,         \* one intended transfer per client, and the client name is its idempotency key
    Workers, Transfer, Opening,
    MaxFaults,       \* crashes, dropped requests and dropped responses share one budget
    MaxEagerRetries,
    SkipKeyCheck     \* TRUE removes the idempotency check, to show the checker is not vacuous

All == Accounts \cup {World}
NoWorker == "none"
Outcomes == {"applied", "insufficient"}

VARIABLES bal, postings, txn, locks, held, net, resp, wk, cl, got, faults, eager

vars == <<bal, postings, txn, locks, held, net, resp, wk, cl, got, faults, eager>>

RECURSIVE SumSeq(_)
SumSeq(s) == IF s = <<>> THEN 0 ELSE Head(s).amt + SumSeq(Tail(s))

RECURSIVE SumFn(_, _)
SumFn(f, S) == IF S = {} THEN 0 ELSE LET x == CHOOSE y \in S : TRUE IN f[x] + SumFn(f, S \ {x})

PostingsOf(a) == SelectSeq(postings, LAMBDA p : p.acct = a)
PostingsFor(k) == SelectSeq(postings, LAMBDA p : p.key = k)

\* One lock order for every transfer, the UUID order in the code. It is what rules out a waits-for cycle.
Ord == CHOOSE f \in [Accounts -> 1..Cardinality(Accounts)] : \A x, y \in Accounts : x # y => f[x] # f[y]
Lower(c) == IF Ord[Transfer[c].from] < Ord[Transfer[c].to] THEN Transfer[c].from ELSE Transfer[c].to
Higher(c) == IF Lower(c) = Transfer[c].from THEN Transfer[c].to ELSE Transfer[c].from

RECURSIVE OpeningSeq(_)
OpeningSeq(S) ==
    IF S = {} THEN <<>>
    ELSE LET a == CHOOSE x \in S : TRUE
         IN OpeningSeq(S \ {a}) \o <<[key |-> "open", acct |-> World, amt |-> -Opening[a]],
                                     [key |-> "open", acct |-> a, amt |-> Opening[a]]>>

Init ==
    /\ bal = [a \in All |-> IF a = World THEN -SumFn(Opening, Accounts) ELSE Opening[a]]
    /\ postings = OpeningSeq(Accounts)
    /\ txn = [c \in Clients |-> "none"]
    /\ locks = [a \in Accounts |-> NoWorker]
    /\ held = [c \in Clients |-> NoWorker]      \* the speculative row of ON CONFLICT DO NOTHING
    /\ net = {}
    /\ resp = {}
    /\ wk = [w \in Workers |-> [pc |-> "idle", c |-> NoWorker, st |-> "none"]]
    /\ cl = [c \in Clients |-> "idle"]
    /\ got = [c \in Clients |-> "none"]
    /\ faults = 0
    /\ eager = 0

Release(w) ==
    /\ locks' = [a \in Accounts |-> IF locks[a] = w THEN NoWorker ELSE locks[a]]
    /\ held' = [c \in Clients |-> IF held[c] = w THEN NoWorker ELSE held[c]]

Idle(w) == wk' = [wk EXCEPT ![w] = [pc |-> "idle", c |-> NoWorker, st |-> "none"]]
Goto(w, pc) == wk' = [wk EXCEPT ![w].pc = pc]
InFlight(c) == c \in net \/ (\E w \in Workers : wk[w].c = c) \/ (\E r \in resp : r.c = c)

----------------------------------------------------------------------------

Send(c) ==
    /\ cl[c] = "idle"
    /\ net' = net \cup {c}
    /\ cl' = [cl EXCEPT ![c] = "waiting"]
    /\ UNCHANGED <<bal, postings, txn, locks, held, resp, wk, got, faults, eager>>

\* Nothing of c is left anywhere, so the client times out and sends the same key and body again.
Retry(c) ==
    /\ cl[c] = "waiting"
    /\ ~InFlight(c)
    /\ net' = net \cup {c}
    /\ UNCHANGED <<bal, postings, txn, locks, held, resp, wk, cl, got, faults, eager>>

\* A retry fired too early, while the first copy is still being handled, so two copies of one key race.
EagerRetry(c) ==
    /\ cl[c] = "waiting"
    /\ c \notin net
    /\ eager < MaxEagerRetries
    /\ net' = net \cup {c}
    /\ eager' = eager + 1
    /\ UNCHANGED <<bal, postings, txn, locks, held, resp, wk, cl, got, faults>>

Receive(c) ==
    \E r \in resp :
        /\ r.c = c
        /\ resp' = resp \ {r}
        /\ IF cl[c] = "waiting"
           THEN /\ cl' = [cl EXCEPT ![c] = "done"]
                /\ got' = [got EXCEPT ![c] = r.o]
           ELSE UNCHANGED <<cl, got>>
        /\ UNCHANGED <<bal, postings, txn, locks, held, net, wk, faults, eager>>

----------------------------------------------------------------------------

Take(w) ==
    /\ wk[w].pc = "idle"
    /\ \E c \in net :
        /\ net' = net \ {c}
        /\ wk' = [wk EXCEPT ![w] = [pc |-> "fast", c |-> c, st |-> "none"]]
    /\ UNCHANGED <<bal, postings, txn, locks, held, resp, cl, got, faults, eager>>

FastPath(w) ==
    LET c == wk[w].c IN
    /\ wk[w].pc = "fast"
    /\ IF txn[c] # "none" /\ ~SkipKeyCheck
       THEN /\ resp' = resp \cup {[c |-> c, o |-> txn[c]]}
            /\ Idle(w)
       ELSE /\ Goto(w, "lock1")
            /\ UNCHANGED resp
    /\ UNCHANGED <<bal, postings, txn, locks, held, net, cl, got, faults, eager>>

Lock(w, pc, acct, next) ==
    /\ wk[w].pc = pc
    /\ locks[acct] = NoWorker
    /\ locks' = [locks EXCEPT ![acct] = w]
    /\ Goto(w, next)
    /\ UNCHANGED <<bal, postings, txn, held, net, resp, cl, got, faults, eager>>

Lock1(w) == Lock(w, "lock1", Lower(wk[w].c), "lock2")
Lock2(w) == Lock(w, "lock2", Higher(wk[w].c), "insert")

\* INSERT ... ON CONFLICT DO NOTHING. A committed row means replay. Another open transaction holding the
\* same key blocks this one until it commits or rolls back, which is what Postgres does.
Insert(w) ==
    LET c == wk[w].c
        t == Transfer[c]
    IN
    /\ wk[w].pc = "insert"
    /\ \/ /\ txn[c] # "none" /\ ~SkipKeyCheck
          /\ resp' = resp \cup {[c |-> c, o |-> txn[c]]}
          /\ Release(w)
          /\ Idle(w)
       \/ /\ txn[c] = "none" \/ SkipKeyCheck
          /\ held[c] \in {NoWorker, w} \/ SkipKeyCheck
          /\ held' = [held EXCEPT ![c] = w]
          /\ wk' = [wk EXCEPT ![w].pc = "commit",
                              ![w].st = IF bal[t.from] >= t.amt THEN "applied" ELSE "insufficient"]
          /\ UNCHANGED <<locks, resp>>
    /\ UNCHANGED <<bal, postings, txn, net, cl, got, faults, eager>>

Commit(w) ==
    LET c == wk[w].c
        t == Transfer[c]
        st == wk[w].st
    IN
    /\ wk[w].pc = "commit"
    /\ txn' = [txn EXCEPT ![c] = st]
    /\ IF st = "applied"
       THEN /\ bal' = [bal EXCEPT ![t.from] = @ - t.amt, ![t.to] = @ + t.amt]
            /\ postings' = postings \o <<[key |-> c, acct |-> t.from, amt |-> -t.amt],
                                         [key |-> c, acct |-> t.to, amt |-> t.amt]>>
       ELSE UNCHANGED <<bal, postings>>
    /\ resp' = resp \cup {[c |-> c, o |-> st]}
    /\ Release(w)
    /\ Idle(w)
    /\ UNCHANGED <<net, cl, got, faults, eager>>

----------------------------------------------------------------------------
\* Faults. A crash is a killed pod or a severed database connection, and the open transaction rolls back.

Crash(w) ==
    /\ faults < MaxFaults
    /\ wk[w].pc # "idle"
    /\ Release(w)
    /\ Idle(w)
    /\ faults' = faults + 1
    /\ UNCHANGED <<bal, postings, txn, net, resp, cl, got, eager>>

DropRequest ==
    /\ faults < MaxFaults
    /\ \E c \in net : net' = net \ {c}
    /\ faults' = faults + 1
    /\ UNCHANGED <<bal, postings, txn, locks, held, resp, wk, cl, got, eager>>

DropResponse ==
    /\ faults < MaxFaults
    /\ \E r \in resp : resp' = resp \ {r}
    /\ faults' = faults + 1
    /\ UNCHANGED <<bal, postings, txn, locks, held, net, wk, cl, got, eager>>

----------------------------------------------------------------------------

WorkerStep(w) == Take(w) \/ FastPath(w) \/ Lock1(w) \/ Lock2(w) \/ Insert(w) \/ Commit(w)
ClientStep(c) == Send(c) \/ Retry(c) \/ EagerRetry(c) \/ Receive(c)

\* Every client answered and nothing left in flight. Stuttering here is the end, not a deadlock, so TLC
\* still reports a real one, such as two workers each holding the lock the other wants.
Terminated ==
    /\ \A c \in Clients : cl[c] = "done"
    /\ net = {} /\ resp = {}
    /\ \A w \in Workers : wk[w].pc = "idle"
    /\ UNCHANGED vars

Next ==
    \/ Terminated
    \/ \E c \in Clients : ClientStep(c)
    \/ \E w \in Workers : WorkerStep(w) \/ Crash(w)
    \/ DropRequest \/ DropResponse

\* Fairness on the honest steps only. Faults are never forced, and they run out after MaxFaults.
Fairness ==
    /\ \A w \in Workers : WF_vars(WorkerStep(w))
    /\ \A c \in Clients : WF_vars(Send(c)) /\ WF_vars(Retry(c)) /\ WF_vars(Receive(c))

Spec == Init /\ [][Next]_vars /\ Fairness

----------------------------------------------------------------------------

TypeOK ==
    /\ txn \in [Clients -> {"none"} \cup Outcomes]
    /\ cl \in [Clients -> {"idle", "waiting", "done"}]
    /\ got \in [Clients -> {"none"} \cup Outcomes]
    /\ faults \in 0..MaxFaults

Conservation == SumFn(bal, All) = 0

PostingsSumToZero == SumSeq(postings) = 0

NoNegativeBalance == \A a \in Accounts : bal[a] >= 0

Reconciles == \A a \in All : bal[a] = SumSeq(PostingsOf(a))

ExactlyOnce == \A c \in Clients : Len(PostingsFor(c)) = IF txn[c] = "applied" THEN 2 ELSE 0

NoPhantom == \A i \in 1..Len(postings) : postings[i].key \in Clients \cup {"open"}

\* A client that got an answer got the one the book holds, whichever copy of its request won.
AnswerMatchesBook == \A c \in Clients : got[c] # "none" => got[c] = txn[c]

EveryClientAnswered == <>[](\A c \in Clients : cl[c] = "done")
=============================================================================
