Threshold 40. Normal postings: 8991, flagged 240 (false positive rate 0.0267).

| Pattern | Postings | Episodes | Precision | Recall | AUROC | Episodes detected | Latency mean | Latency median |
|---|---|---|---|---|---|---|---|---|
| burst | 127 | 15 | 0.0204 | 0.0394 | 0.8722 | 5 of 15 | 5.6 | 4 |
| structuring | 78 | 15 | 0.2453 | 1.0000 | 0.9933 | 15 of 15 | 0 | 0 |
| account_takeover | 25 | 15 | 0.0698 | 0.7200 | 0.9906 | 15 of 15 | 0 | 0 |
| mule_chain | 69 | 15 | 0.1045 | 0.4058 | 0.8370 | 15 of 15 | 0 | 0 |
| all fraud | 299 | 60 | 0.3496 | 0.4314 | 0.9055 | 50 of 60 | 0.56 | 0 |

Precision for a pattern counts that pattern's flagged postings against every flagged normal posting.
Latency is the number of fraud postings in an episode before the first flagged one, over detected episodes.
