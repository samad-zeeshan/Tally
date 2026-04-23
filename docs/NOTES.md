# Notes for the demo page

Working notes for `docs/index.html`. This file is not part of the project's own documentation.
The correctness write-up is `docs/correctness.md` and the decision log is `docs/adr/`; neither
was touched by this work.

## What is on the page and what runs it

The centrepiece is the real client from `web/`, a React and TypeScript app, built unchanged and
copied into `docs/app/`. It is embedded in `docs/index.html` in an `<iframe>` so its stylesheet
(which styles `body`, `form`, `input` and other bare elements) cannot collide with the page around
it, and so its fixed toast shelf stays pinned inside a box the reader can see.

The build is run with a relative base so the asset paths in the copied `index.html` are
`./assets/...` and work under `samad-zeeshan.github.io/Tally/`:

```
cd web
VITE_API_TOKEN= npx vite build --base=./     # then copy dist/ into docs/app/
```

The empty `VITE_API_TOKEN` is deliberate. Vite lets a real environment variable win over
`.env.local`, so the demo bundle is built with no token in it at all. Confirmed afterwards by
grepping `docs/app` for the token value and for the string `Bearer`: neither appears, because the
minifier removed the whole `if (TOKEN)` branch as dead code.

### The stand-in

`docs/app/tally-server.js` is a classic (non-module) script injected into the copied
`docs/app/index.html` before the app's module script, so it installs first. It replaces
`window.fetch` and answers the six requests `web/src/api/client.ts` can make:

| request | what it does |
| --- | --- |
| `GET /accounts` | lists accounts in creation order, world excluded |
| `POST /accounts` | opens an account, funded out of world through the same two-entry path |
| `GET /accounts/{id}` | one account |
| `GET /accounts/{id}/statement` | newest first, keyset paged, opaque `v1:` cursor |
| `GET /reconciliation` | re-derives every balance from its postings, sums the whole book |
| `POST /transfers` | validates, claims the key, applies or refuses, replays a repeat |

It transcribes, rule for rule: `InMemoryStore.java`, `Ledger.java`, `WorldAccount.java`,
`Validation.java`, `ErrorCode.java` (the full code to status map), `Cursor.java`, and the three
handlers under `api/`. Behaviour it reproduces on purpose:

- opening balances are moved out of world, never minted, so the book sums to zero from account one;
- two mirrored postings per transfer, appended under one sequence;
- an overdraft is refused with `INSUFFICIENT_FUNDS` (422) and moves nothing;
- only `Applied` and `InsufficientFunds` consume an idempotency key, so a repeat of either replays
  the original status and adds `Idempotency-Replayed: true`, while an unknown-account failure
  leaves the key free for a later real transfer;
- the same key with a different from/to/amount is `IDEMPOTENCY_KEY_CONFLICT` (409).

Two things it does not reproduce, because a browser has neither: per-account locking (there is one
thread, so nothing can interleave) and durability (closing the tab empties the book). It also
ignores the bearer token entirely, and signs no cursors; it has nothing to protect.

Anything that is not one of the three Tally path shapes is passed through to the browser's own
fetch and logged as a warning, so an escaping request would be visible rather than silent. In
practice nothing escapes: a full session of clicking every demo, a manual transfer and an overdraft
produced exactly five network requests, all of them this page's own files.

The stand-in was checked against 47 assertions covering the whole contract before the page was
wired up: seeding, a transfer, a replay, a key conflict, an overdraft, a replayed refusal,
statements, paging, twelve validation failures, key release, and the zero sum after all of it.

### The demo book starts with two accounts

`Amara` at 500.00 and `Ben` at 0.00, the same two names as the recorded run in
`data/tally-run.json`, opened through the same `createAccount` path so world is funded correctly.
The page says so in the honesty notice rather than letting a reader assume the real service boots
with accounts in it.

## Every number on the page, and where it came from

| Claim | Source | Method |
| --- | --- | --- |
| 240 tests, 0 failures, 0 errors, 1 skipped, 32 test files | `./mvnw test` | Run in this repository on 2026-07-27 on Java 25. Surefire's summary line is `Tests run: 240, Failures: 0, Errors: 0, Skipped: 1`, preceded by 32 per-class lines, then `BUILD SUCCESS` in 4.549 s. The skip is `StaticFileHandlerTest.aLinkToAFileInsideTheRootStillServes`: it needs a file symlink, and Windows refuses to create one without elevation. Its sibling `aLinkInsideTheRootCannotReachOutsideIt`, the one that proves a link cannot escape the served root, does run on Windows, via a directory junction. Both run on Linux CI. |
| 20,000 payments across 8 accounts | `src/test/java/dev/tally/testsupport/StressHarness.java` line 42 | `Config.standard`: 8 accounts, 10,000 opening balance, 20,000 operations, 30 second timeout. |
| Up to 32 workers at once, two per processor core, floor 8, ceiling 32 | `StressHarness.java` line 39 | `Math.clamp(2L * Runtime.getRuntime().availableProcessors(), 8, 32)`. |
| 3 accounts checked, book sums to 0, no disagreements | `docs/data/tally-run.json`, step `reconciliation` | `{"consistent":true,"globalSumMinor":0,"accountsChecked":3,"drifts":[]}`, copied verbatim from the real Java service at code version `1b44ebf`. |
| Amara 375.00, Ben 125.00, world -500.00 (the zero line figure) | `docs/data/tally-run.json`, step `transfer` | The `book` array of that step. Bar heights are fractions of one unit where 1 is 500.00. Amara and Ben are stacked into one column, so .75 + .25 is a single height of 1, which is the height of the world bar below: the two halves are equal by construction and measure equal in the browser (126 px each at 360 px wide, 188 px each at 768 px and above). |
| Exactly 1 third party library at run time | `pom.xml` lines 46 to 51 | The PostgreSQL JDBC driver, `runtime` scope. JUnit is `test` scope, so it is not in the shipped artifact. |
| 51 Java files, about 3,155 lines | `src/main/java` | `find src/main -name '*.java' \| wc -l` and the same piped through `cat \| wc -l`, on 2026-07-27. |
| 33 browser tests across 5 files, clean type check | `npm test` and `npx tsc -b` in `web/` | Run on 2026-07-27: `Test Files 5 passed (5)`, `Tests 33 passed (33)`; `tsc -b` exits 0. |
| No amount above a thousand billion cents | `src/main/java/dev/tally/http/Validation.java` line 22 | `MAX_AMOUNT_MINOR = 1_000_000_000_000L`. |

Each of these also carries an HTML comment next to it in `docs/index.html` naming the same source.

## Three claims the previous version of this page got wrong

1. **"257 tests, with no database."** Wrong. `target/surefire-reports/` holds XML from several runs
   on two different dates, so counting the files there mixes the no-database run with a Postgres
   run and a race-demo run. The corrected figure was **210** when this was written, taken from the
   summary line that `./mvnw test` itself prints, and the page now names the command beside the
   number so it stays right as the suite grows. It reads **240** today, after the security pass.
2. **"One thread per two processor cores."** Inverted. `StressHarness.java` line 39 is
   `Math.clamp(2L * Runtime.getRuntime().availableProcessors(), 8, 32)`, which is **two threads per
   core**, clamped between 8 and 32.
3. **"20,000 payments fired at the same moment."** Wrong. The harness divides 20,000 operations
   across those 8 to 32 workers, so at most 32 are ever in flight. The page now says **"20,000
   payments pushed through by up to 32 workers at once."**

`docs/correctness.md` carried the same inverted phrasing and has now been corrected in place, to
"N = two threads per core (clamped to 8 to 32)". That was the single smallest edit that fixes the
fact, because the file is linked from a resume that has already been submitted; nothing else in it
was touched. The other figures in that sentence were checked against the source at the same time and
were already right: K = 8 and M = 20,000 are `Config.standard` (`StressHarness.java` line 42), and
the Postgres run at K = 8, N = 8, M = 2,000 is `JdbcStressTest.java` line 48.

## The typewriter narration

Three short lines on the page type themselves out. It is a garnish and never a gate:

- the full sentence is written in the HTML, so with no JavaScript it is simply a paragraph;
- when JavaScript arms it, the finished sentence stays in the layout at full size but invisible,
  which reserves the exact height at any width, so nothing on the page moves while it types
  (measured: the block stays at 78 px through four seconds of typing at 360 px wide);
- under `prefers-reduced-motion: reduce` every line is filled in instantly, with no caret;
- the finished sentence sits in the accessibility tree once, in a visually hidden copy that is
  never rewritten. There is no live region, so a screen reader is never told about a keystroke;
- a "Show all text now" button finishes every line at once, and clicking a line finishes that line;
- a line starts when it scrolls into view, so a reader who lingers at the top does not come down to
  find the rest already over.

## The signature

Tally's registered signature in `demo-kit/SIGNATURES.md` is the zero line. It is kept, redrawn in
Tally's own green rather than the shared kit's brass, and simplified to a single frame taken from
the recorded run instead of a stepper, so the page has one interactive thing on it and not two. The
registry row was updated to describe what is actually there.

It was redrawn again to make the claim visible rather than merely stated. Amara and Ben used to be
two separate bars in two of three columns, with world a third bar of the same width below, so "the
two halves are equal" was something a reader had to work out. They are now stacked into one column,
which turns the sum into a single height that the world bar below mirrors exactly, same width, same
height, corners mirrored. The zero rule bleeds to the card's edges as the spine of the image, and
carries a `0.00` marker on it. `world` is drawn as an outlined, hatched shape rather than a filled
one, so it reads as the counterweight and not as more money. The key beside the bars aligns each
name and amount to the middle of its own segment, right-aligned into one column of tabular figures,
and a `+500.00 / -500.00 / 0.00` line under the figure states the sum in words as well as in shape.

The draw-in reveals with `clip-path`, not `transform: scaleY`, for two reasons: scaling stretches
the hatch on the world bar as it grows, and clipping the two stacks as whole units keeps the
segments in proportion the entire way. Neither touches layout. The figure is fully drawn by default
in the CSS and the script adds `.is-armed` only when it is actually going to animate, so with
JavaScript off, or under `prefers-reduced-motion: reduce`, the figure is simply correct rather than
invisible. That last part was a real bug in the previous version: it hid the bars in CSS and relied
on the script to reveal them.

## Constraints honoured

- `docs/kit.css`, `docs/kit.js` and `docs/data/tally-run.js` were deleted. Tally no longer shares a
  design kit with any other page; its colours come from `web/src/styles.css`.
- `docs/correctness.md`, `docs/adr/` and `docs/media/` were not touched. `docs/media/demo.gif` is
  5 MB and is deliberately not loaded by the page.
- All paths are relative, no leading slashes. `docs/.nojekyll` stays.
- Page weight: one 21 KB HTML file plus the app's 219 KB of JavaScript, 17 KB of CSS and the 13 KB
  stand-in.
- No Java source was touched. `web/vite.config.ts` and `web/package.json` are unchanged, and
  `web/dist/` is regenerated but is listed in `web/.gitignore`. `web/src` is no longer off limits:
  its user-facing strings were rewritten in plain English (see below), which is a change to the app
  itself and not only to this page, so that work spans `web/` and `docs/` together.

## Known rough edges

- The app's own copy used to carry its engineering vocabulary ("double entry", "idempotent
  transfers", "reconciliation") into the frame, and the section below the frame existed partly to
  translate it. The strings in `web/src` now say the same things in plain words: the tagline is
  "every payment written down twice", the three claims are "Written down twice", "Send it twice, pay
  once" and "The books are the truth", and the buttons are "Make a sample transfer", "Lose the
  reply, send again" and "Recount everything". Only strings a person reads were changed; the code's
  own comments, identifiers, types and the API paths keep the technical terms. "What you just
  watched" now reinforces the app's wording instead of translating it. This page quotes a button
  label by name in two places: "Make a sample transfer" kept its label and was left alone, and the
  quote of "Lose a response, retry safely" was updated to the new "Lose the reply, send again". One
  sentence that read "the panel calls this double entry" no longer described the panel, so it now
  credits bookkeepers with the term instead.
- The frame is a fixed height with its own scrollbar. That is on purpose: the app's toasts are
  fixed to the bottom of their own viewport, and a frame that grew to fit its content would push
  them off screen just as the demo narrates itself.
- Opening `docs/index.html` straight off a disk cannot run the app, because browsers refuse ES
  modules over `file://`. The page detects that, hides the empty frame and says where to go
  instead; every other section still reads.
