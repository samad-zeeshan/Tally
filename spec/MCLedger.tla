------------------------------ MODULE MCLedger ------------------------------
(* The model TLC checks: two accounts, three clients whose transfers cross, two workers.

   Whether c2 and c3 have the funds depends on which transfers landed first, so both outcomes get explored. *)
EXTENDS Ledger

MCTransfer == [c \in {"c1", "c2", "c3"} |->
                 CASE c = "c1" -> [from |-> "a", to |-> "b", amt |-> 2]
                   [] c = "c2" -> [from |-> "b", to |-> "a", amt |-> 3]
                   [] c = "c3" -> [from |-> "a", to |-> "b", amt |-> 1]]

MCOpening == [a \in {"a", "b"} |-> IF a = "a" THEN 2 ELSE 1]
=============================================================================
