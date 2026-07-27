#!/usr/bin/env python3
"""Score a suspicions table against the OWASP Benchmark ground truth.

The benchmark is 2,740 servlets: 1,415 genuinely vulnerable and 1,325 DECOYS that look vulnerable and
are not. That second half is the point — it measures precision, which is where an LLM bug-finder is
weakest. Recall alone would reward flagging everything.

Usage:  score_benchmark.py <suspicions.json|-> [expectedresults.csv]
        (suspicions.json = the export produced by the dashboard/DB dump)
"""
import csv, json, os, re, sys, collections

HERE = os.path.dirname(os.path.abspath(__file__))
GT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, "benchmark_expectedresults.csv")

def load_truth(path):
    truth = {}
    for r in csv.DictReader(open(path)):
        name = (r.get("# test name") or "").strip()
        if not name:
            continue
        truth[name.lower()] = {
            "real": str(r.get(" real vulnerability", "")).strip().lower() == "true",
            "cwe": (r.get(" cwe") or "").strip(),
            "category": (r.get(" category") or "").strip(),
        }
    return truth

def case_of(path):
    """BenchmarkTestNNNNN from a repo-relative file path."""
    m = re.search(r"(BenchmarkTest\d+)", path or "")
    return m.group(1).lower() if m else None

def main():
    raw = sys.stdin.read() if sys.argv[1] == "-" else open(sys.argv[1]).read()
    data = json.loads(raw)
    sus = data.get("suspicions", data) if isinstance(data, dict) else data
    truth = load_truth(GT)

    flagged = {}                       # case -> best severity we reported
    order = {"high": 3, "medium": 2, "low": 1}
    for s in sus:
        c = case_of(s.get("file"))
        if not c or c not in truth:
            continue
        sev = (s.get("severity") or "").lower()
        if order.get(sev, 0) >= order.get(flagged.get(c, ""), 0):
            flagged[c] = sev

    tp = [c for c in flagged if truth[c]["real"]]
    fp = [c for c in flagged if not truth[c]["real"]]
    real_total = sum(1 for t in truth.values() if t["real"])

    print("scanned cases flagged : %d" % len(flagged))
    print("true positives        : %d / %d real  (recall %.1f%%)"
          % (len(tp), real_total, 100.0 * len(tp) / max(real_total, 1)))
    print("false positives       : %d  (flagged a decoy)" % len(fp))
    if flagged:
        print("precision             : %.1f%%" % (100.0 * len(tp) / len(flagged)))
        # the Benchmark's own headline metric: recall minus false-positive rate
        decoy_total = len(truth) - real_total
        fpr = len(fp) / max(decoy_total, 1)
        print("Benchmark score       : %.1f%%  (recall - false-positive rate)"
              % (100.0 * (len(tp) / max(real_total, 1) - fpr)))
    by_cwe = collections.Counter(truth[c]["category"] for c in tp)
    if by_cwe:
        print("\nfound, by category:")
        for k, v in by_cwe.most_common():
            tot = sum(1 for t in truth.values() if t["real"] and t["category"] == k)
            print("   %-14s %4d / %-4d" % (k, v, tot))

if __name__ == "__main__":
    main()
