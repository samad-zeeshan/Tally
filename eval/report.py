"""
Generate the README's result tables and the demo's numbers from the files under eval/.

--check fails if the README or site/data differ from what the files say, which is the drift gate in CI.
"""

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
README = ROOT / "README.md"
SITE = ROOT / "site" / "data"


def load(rel):
    return json.loads((ROOT / rel).read_text(encoding="utf-8"))


def f4(v):
    return "n/a" if v is None else f"{v:.4f}".rstrip("0").rstrip(".") if v not in (0, 1) else f"{v:g}"


def spec_table(spec):
    rows = ["| Model | Expected | Result | Distinct states | Invariant broken |", "|---|---|---|---|---|"]
    labels = {"MCLedger": "the real protocol", "MCLedgerNoKeyCheck": "idempotency check removed"}
    for name, r in spec["runs"].items():
        rows.append(f"| {labels.get(name, name)} | {r['expect']} | {r['result']} | {r['distinct_states']:,} | "
                    f"{', '.join(r['violated']) or 'none'} |")
    return "\n".join(rows)


def faults_table(faults):
    rows = ["| Fault | Transfers | Retries | Money created or lost | Applied twice | Phantom | Confirmed but missing | Reconciles | Longest wait for an answer |",
            "|---|---|---|---|---|---|---|---|---|"]
    for r in faults["runs"]:
        rows.append(f"| {r['fault'].replace('_', ' ')} | {r['keys']:,} | {r['retries']:,} | {r['conservation_violations']} | "
                    f"{r['double_applications']} | {r['phantom_transfers']} | {r['lost_acknowledged_transfers']} | "
                    f"{'yes' if r['reconciliation_consistent'] else 'no'} | {r['longest_gap_seconds']} s |")
    return "\n".join(rows)


def load_table(ld):
    rows = ["| Operation | p99 budget | Max sustained | p50 at that load | p95 | p99 |", "|---|---|---|---|---|---|"]
    for op, r in ld["results"].items():
        best = next((s for s in r["steps"] if s["offered_rps"] == r["max_sustained_rps"]), None)
        if best is None:
            rows.append(f"| {op} | {r['p99_budget_ms']} ms | none within budget | | | |")
            continue
        rows.append(f"| {op} | {r['p99_budget_ms']} ms | {best['offered_rps']} req/s | {best['p50_ms']} ms | "
                    f"{best['p95_ms']} ms | {best['p99_ms']} ms |")
    return "\n".join(rows)


def fraud_table(fr):
    v1, v2 = fr["v1"], fr["v2"]
    rows = ["| At the flag line of 40 | v1 rules | v2 with graph rules |", "|---|---|---|",
            f"| Precision, all fraud | {f4(v1['all_fraud']['precision'])} | {f4(v2['all_fraud']['precision'])} |",
            f"| Recall, all fraud | {f4(v1['all_fraud']['recall'])} | {f4(v2['all_fraud']['recall'])} |",
            f"| AUROC, all fraud | {f4(v1['all_fraud']['auroc'])} | {f4(v2['all_fraud']['auroc'])} |",
            f"| Normal postings flagged | {v1['normal']['flagged']} of {v1['normal']['postings']:,} | "
            f"{v2['normal']['flagged']} of {v2['normal']['postings']:,} |",
            f"| Alerts per 1,000 postings | {v1['alerts_per_1000_postings']} | {v2['alerts_per_1000_postings']} |"]
    h = fr["holdout"]
    rows.append(f"| Precision / recall on a fresh seed ({h['seed']}) | {f4(h['v1']['all_fraud']['precision'])} / "
                f"{f4(h['v1']['all_fraud']['recall'])} | {f4(h['v2']['all_fraud']['precision'])} / {f4(h['v2']['all_fraud']['recall'])} |")
    return "\n".join(rows)


def pattern_table(fr):
    rows = ["| Pattern | v1 precision at its recall | v2 precision at the same recall | v1 AUROC | v2 AUROC | v2 recall at 40 |",
            "|---|---|---|---|---|---|"]
    for p, r in fr["precision_at_v1_recall"].items():
        if p == "all_fraud":
            continue
        rows.append(f"| {p.replace('_', ' ')} | {f4(r['v1_precision'])} at {f4(r['v1_recall'])} | {f4(r['v2']['precision'])} | "
                    f"{f4(fr['v1']['patterns'][p]['auroc'])} | {f4(fr['v2']['patterns'][p]['auroc'])} | "
                    f"{f4(fr['v2']['patterns'][p]['recall'])} |")
    return "\n".join(rows)


def reflection_table(rf):
    rows = ["| Run | Proposed rule | z3 check | Replay gate |", "|---|---|---|---|"]
    for r in rf["runs"]:
        rule = r.get("rule")
        text = f"`{rule['feature']} {rule['op']} {rule['threshold']}`, {rule['points']} points" if rule else "no valid rule"
        smt = r["smt"]["verdict"] + ("" if r["smt"]["verdict"] == "passed" else f" ({r['smt']['reason'].replace('_', ' ')})")
        g = r["gate"]
        gate = g["verdict"].replace("_", " ") + (f" ({'; '.join(g['why'])})" if g.get("why") else "")
        rows.append(f"| {r['run']} | {text} | {smt} | {gate} |")
    return "\n".join(rows)


def texts(spec, faults, ld, fr, rf):
    burst = fr["precision_at_v1_recall"]["burst"]
    ex = fr["explanations"]
    pit = fr["point_in_time"]
    t = faults["totals"]
    return {
        "faults-summary": f"{len(faults['runs'])} runs, {t['keys']:,} transfers and {t['retries']:,} retries, "
                          f"{t['violations']} violations of any kind.",
        "fraud-summary": f"Dataset: {fr['dataset']['transfers']:,} transfers between {fr['dataset']['accounts']} accounts, seed "
                         f"{fr['dataset']['seed']}. Burst precision at v1's recall of {f4(burst['v1_recall'])} went from {f4(burst['v1_precision'])} "
                         f"to {f4(burst['v2']['precision'])}. Across all fraud, normal postings flagged fell from "
                         f"{fr['v1']['normal']['flagged']} to {fr['v2']['normal']['flagged']}.",
        "pit-summary": f"The verifier recomputed {pit['features_per_posting']} features for all {pit['postings_checked']:,} "
                       f"postings and found {pit['java_python_mismatches']} mismatches with the Java scorer, and "
                       f"{pit['future_perturbation_leaks']} changes when the future was rewritten for "
                       f"{pit['future_perturbation_samples']} sampled postings. Building an explanation took "
                       f"{ex['p99_micros']} microseconds at p99, against a budget of {ex['budget_micros'] // 1000} ms.",
    }


def numbers(spec, faults, ld, fr, rf):
    burst = fr["precision_at_v1_recall"]["burst"]
    tr = ld["results"]["transfer"]
    best = next((s for s in tr["steps"] if s["offered_rps"] == tr["max_sustained_rps"]), None)
    return {
        "tiles": [
            {"label": "States the model checker explored", "value": f"{spec['runs']['MCLedger']['distinct_states']:,}",
             "was": "no violation, and with the key check removed it finds a double payment"},
            {"label": "Transfers under injected faults", "value": f"{faults['totals']['keys']:,}",
             "was": f"{faults['totals']['violations']} violations across {len(faults['runs'])} fault runs"},
            {"label": "Transfer p99 on the cluster", "value": f"{best['p99_ms']} ms" if best else "n/a",
             "was": f"at {tr['max_sustained_rps']} requests a second" if best else ""},
            {"label": "Burst alert precision", "value": f4(burst["v2"]["precision"]),
             "was": f"was {f4(burst['v1_precision'])} at the same recall"},
            {"label": "Normal payments flagged", "value": str(fr["v2"]["normal"]["flagged"]),
             "was": f"was {fr['v1']['normal']['flagged']} of {fr['v1']['normal']['postings']:,}"},
        ],
        "sources": ["eval/spec/results.json", "eval/faults/results.json", "eval/load/results.json", "eval/fraud/results.json"],
    }


def render():
    spec, faults, ld = load("eval/spec/results.json"), load("eval/faults/results.json"), load("eval/load/results.json")
    fr, rf = load("eval/fraud/results.json"), load("eval/fraud/reflection.json")
    blocks = {"spec": spec_table(spec), "faults": faults_table(faults), "load": load_table(ld), "fraud": fraud_table(fr),
              "patterns": pattern_table(fr), "reflection": reflection_table(rf), **texts(spec, faults, ld, fr, rf)}
    mixed = next(r for r in faults["runs"] if r["fault"] == "mixed")
    timeline = load(f"eval/faults/runs/{mixed['fault']}.json")
    site = {"numbers.json": numbers(spec, faults, ld, fr, rf), "faults-run.json": timeline}
    return blocks, site


# Markers are HTML comments, so they do not show on GitHub: <!-- gen:name --> ... <!-- /gen -->
MARK = re.compile(r"(<!-- gen:([a-z-]+) -->\n)(.*?)(<!-- /gen -->)", re.S)


def apply(readme, blocks):
    missing = set(blocks) - set(m.group(2) for m in MARK.finditer(readme))
    if missing:
        raise SystemExit(f"README has no block for {sorted(missing)}")
    return MARK.sub(lambda m: m.group(1) + blocks[m.group(2)] + "\n" + m.group(4), readme)


def main():
    parser = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    blocks, site = render()
    readme = README.read_text(encoding="utf-8")
    new = apply(readme, blocks)
    files = {README: new, **{SITE / k: json.dumps(v, indent=1) + "\n" for k, v in site.items()}}
    drift = [str(p.relative_to(ROOT)) for p, text in files.items()
             if not p.exists() or p.read_text(encoding="utf-8") != text]
    if args.check:
        if drift:
            print("out of date with eval/: " + ", ".join(drift) + ". Run python eval/report.py.")
            sys.exit(1)
        print("README and site/data match eval/")
        return
    for p, text in files.items():
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8", newline="\n")
    print("wrote " + (", ".join(drift) if drift else "nothing, already current"))


if __name__ == "__main__":
    main()
