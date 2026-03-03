# Architecture decisions

These are the decisions worth remembering, one file each, in the order they were made.
Each records what was chosen, the alternatives, and why, so the reasoning outlives the
commit that changed the code.

| Number | Decision | Area |
|--------|----------|------|
| 0001 | Build with Maven through the wrapper | build |
| 0002 | Money is a long in integer minor units | core |
| 0003 | Outcomes are sealed values, not exceptions | core |
| 0004 | A world account funds openings and may go negative | core |
| 0005 | UUID ids for accounts and transfers, bigserial for postings | core |
