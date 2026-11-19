#!/usr/bin/env python3
"""Statistical analysis of the container experiments.

Reads every measurements-*.csv written by experiments/container/measure-images.ps1
(columns: timestamp, metric, service, variant, run, value, unit) and writes

  report.md   descriptive statistics, normality and significance tests, size/time savings
  stats.csv   the same numbers in machine-readable form

Measurement Study 1, variants single / multi-jdk / multi-jre / multi:
  repeated metrics  -> Shapiro-Wilk per variant, Kruskal-Wallis across the four variants,
                       pairwise Mann-Whitney U with Bonferroni correction as post-hoc test
                       (build/startup time, idle memory, and the load test: throughput, p99, peak memory)
  image sizes       -> deterministic (one value per build), reported with % reduction per step
Experiment 2 (cache efficiency), 2x2 design order x cache mount:
  variants cache-wrong / cache-right / cache-wrong-mount / cache-right-mount
  per scenario S1 (cold), S2 (reused machine), S3 (incremental) -> Shapiro-Wilk per variant,
  Kruskal-Wallis across the four variants, pairwise Mann-Whitney U with Bonferroni correction

Usage:  python analyze.py <dir with measurements-*.csv> [--out <dir>] [--alpha 0.05]
"""
import argparse
import csv
import glob
import itertools
import os
import statistics
import sys

from scipy import stats

EXP1_VARIANTS = ["single", "multi-jdk", "multi-jre", "multi"]
EXP1_TESTED = ["build_time", "startup_wall", "startup_ready", "memory_idle",
               "load_throughput", "load_p99", "load_mem_peak"]
EXP1_SIZES = ["size_compressed", "size_uncompressed"]
EXP2_VARIANTS = ["cache-wrong", "cache-right", "cache-wrong-mount", "cache-right-mount"]
EXP2_TESTED = ["cache_s1_cold", "cache_s2_reused", "cache_s3_incremental"]

LABELS = {
    "build_time": "Build time, cold (--no-cache)",
    "startup_wall": "Startup: docker run -> /actuator/health 200",
    "startup_ready": "Startup: JVM-reported application_ready_time",
    "memory_idle": "Memory at idle (30 s after healthy)",
    "load_throughput": "Load test: throughput (esg-score-service)",
    "load_p99": "Load test: p99 latency (esg-score-service)",
    "load_mem_peak": "Load test: peak memory (esg-score-service)",
    "size_compressed": "Image size, compressed (registry)",
    "size_uncompressed": "Image size, uncompressed (layers)",
    "cache_s1_cold": "S1 cold build (--no-cache)",
    "cache_s2_reused": "S2 rebuild on a reused machine (layer cache deleted, cache mount kept)",
    "cache_s3_incremental": "S3 incremental rebuild (src/ changed)",
}


def load(directory):
    files = sorted(glob.glob(os.path.join(directory, "**", "measurements-*.csv"), recursive=True))
    if not files:
        sys.exit(f"no measurements-*.csv found under {directory}")
    rows = []
    for path in files:
        with open(path, newline="", encoding="utf-8-sig") as f:
            for row in csv.DictReader(f):
                row["value"] = float(row["value"])
                rows.append(row)
    return files, rows


def grouped(rows):
    """{(metric, service, variant): ([values], unit)}"""
    groups = {}
    for r in rows:
        key = (r["metric"], r["service"], r["variant"])
        groups.setdefault(key, ([], r["unit"]))[0].append(r["value"])
    return groups


def describe(values):
    return {
        "n": len(values),
        "mean": statistics.fmean(values),
        "sd": statistics.stdev(values) if len(values) > 1 else 0.0,
        "median": statistics.median(values),
        "min": min(values),
        "max": max(values),
    }


def shapiro(values):
    """(W, p) or None when the test is not defined (n < 3 or all values equal)."""
    if len(values) < 3 or len(set(values)) == 1:
        return None
    w, p = stats.shapiro(values)
    return w, p


def mann_whitney(a, b):
    if len(set(a + b)) == 1:
        return None
    u, p = stats.mannwhitneyu(a, b, alternative="two-sided")
    return u, p


def kruskal(samples):
    if len(set(v for s in samples for v in s)) == 1:
        return None
    h, p = stats.kruskal(*samples)
    return h, p


def fmt(x, digits=2):
    if x is None:
        return "–"
    if isinstance(x, str):
        return x
    if isinstance(x, int):
        return str(x)
    if abs(x) < 0.001 and x != 0:
        return f"{x:.2e}"
    return f"{x:.{digits}f}"


def pval(p):
    """p-values with 3 decimals, so e.g. 0.048 isn't shown as 0.05 next to 'significant: yes'."""
    return fmt(p, 3)


def table(header, rows):
    lines = ["| " + " | ".join(header) + " |", "|" + "|".join("---" for _ in header) + "|"]
    lines += ["| " + " | ".join(fmt(c) for c in row) + " |" for row in rows]
    return "\n".join(lines)


def services_of(groups, metrics):
    return sorted({s for (m, s, _v) in groups if m in metrics})


def analyse_repeated(groups, metric, services, variants, alpha, out_rows, md):
    """Descriptive table + Shapiro-Wilk per variant + significance test per service."""
    present = [s for s in services if any((metric, s, v) in groups for v in variants)]
    if not present:
        return
    unit = next(groups[k][1] for k in groups if k[0] == metric)
    md.append(f"\n### {LABELS.get(metric, metric)} ({unit})\n")
    desc_rows, test_rows = [], []
    for s in present:
        samples = {}
        for v in variants:
            if (metric, s, v) not in groups:
                continue
            values = groups[(metric, s, v)][0]
            samples[v] = values
            d = describe(values)
            sw = shapiro(values)
            normal = None if sw is None else ("yes" if sw[1] >= alpha else "no")
            desc_rows.append([s, v, d["n"], d["mean"], d["sd"], d["median"], d["min"], d["max"],
                              pval(None if sw is None else sw[1]), normal])
            out_rows.append({"metric": metric, "service": s, "variant": v, "test": "describe+shapiro",
                             **d, "statistic": None if sw is None else sw[0],
                             "p": None if sw is None else sw[1], "unit": unit})

        if len(samples) == 2:
            (va, a), (vb, b) = samples.items()
            r = mann_whitney(a, b)
            diff = statistics.median(b) - statistics.median(a)
            pct = 100 * diff / statistics.median(a) if statistics.median(a) else None
            test_rows.append([s, f"Mann-Whitney U {va} vs {vb}", None if r is None else r[0],
                              pval(None if r is None else r[1]),
                              None if r is None else ("yes" if r[1] < alpha else "no"),
                              f"median {vb} − {va}: {fmt(diff)} {unit} ({fmt(pct, 1)} %)"])
            out_rows.append({"metric": metric, "service": s, "variant": f"{va} vs {vb}",
                             "test": "mann-whitney", "statistic": None if r is None else r[0],
                             "p": None if r is None else r[1], "unit": unit})
        elif len(samples) >= 3:
            r = kruskal(list(samples.values()))
            test_rows.append([s, "Kruskal-Wallis (all variants)", None if r is None else r[0],
                              pval(None if r is None else r[1]),
                              None if r is None else ("yes" if r[1] < alpha else "no"), ""])
            out_rows.append({"metric": metric, "service": s, "variant": "all", "test": "kruskal-wallis",
                             "statistic": None if r is None else r[0], "p": None if r is None else r[1],
                             "unit": unit})
            pairs = list(itertools.combinations(samples, 2))
            for va, vb in pairs:
                r = mann_whitney(samples[va], samples[vb])
                p_adj = None if r is None else min(1.0, r[1] * len(pairs))
                test_rows.append([s, f"  post-hoc {va} vs {vb} (Bonferroni)", None if r is None else r[0],
                                  pval(p_adj), None if r is None else ("yes" if p_adj < alpha else "no"), ""])
                out_rows.append({"metric": metric, "service": s, "variant": f"{va} vs {vb}",
                                 "test": "mann-whitney-bonferroni", "statistic": None if r is None else r[0],
                                 "p": p_adj, "unit": unit})

    md.append(table(["service", "variant", "n", "mean", "sd", "median", "min", "max",
                     "Shapiro-Wilk p", "normal?"], desc_rows))
    md.append("")
    md.append(table(["service", "test", "statistic", "p", f"significant (α={alpha})?", "effect"], test_rows))


def analyse_sizes(groups, services, md, out_rows):
    for metric in EXP1_SIZES:
        present = [s for s in services if any((metric, s, v) in groups for v in EXP1_VARIANTS)]
        if not present:
            continue
        md.append(f"\n### {LABELS[metric]} (MB)\n")
        rows = []
        for s in present:
            val = {v: statistics.median(groups[(metric, s, v)][0])
                   for v in EXP1_VARIANTS if (metric, s, v) in groups}
            def red(a, b):
                return None if a not in val or b not in val else 100 * (val[a] - val[b]) / val[a]
            rows.append([s, val.get("single"), val.get("multi-jdk"), val.get("multi-jre"), val.get("multi"),
                         red("single", "multi-jdk"), red("multi-jdk", "multi-jre"), red("multi-jre", "multi"),
                         red("single", "multi")])
            for v, x in val.items():
                out_rows.append({"metric": metric, "service": s, "variant": v, "test": "value",
                                 "median": x, "unit": "MB"})
        md.append(table(["service", "single", "multi-jdk", "multi-jre", "multi (Alpine)",
                         "Maven/sources removed: single→multi-jdk %", "JDK→JRE: multi-jdk→multi-jre %",
                         "Ubuntu→Alpine: multi-jre→multi %", "total: single→multi %"], rows))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input", help="directory containing measurements-*.csv (searched recursively)")
    ap.add_argument("--out", default=".", help="output directory for report.md and stats.csv")
    ap.add_argument("--alpha", type=float, default=0.05)
    args = ap.parse_args()

    files, rows = load(args.input)
    groups = grouped(rows)
    out_rows = []
    md = ["# Container experiments — statistical analysis", "",
          f"Input: {len(files)} file(s), {len(rows)} measurements. α = {args.alpha}.",
          "Shapiro-Wilk is reported per variant; the significance tests are non-parametric "
          "(Kruskal-Wallis / Mann-Whitney U), so they do not assume normality. With n = 5 per "
          "group the smallest possible two-sided Mann-Whitney p is 0.0079; with 4 variants there are "
          "6 post-hoc pairs, so the smallest Bonferroni-adjusted p is 0.048."]

    exp1_services = services_of(groups, EXP1_TESTED + EXP1_SIZES)
    if exp1_services:
        md.append("\n## Measurement Study 1 — image variants (single vs multi-jdk vs multi-jre vs multi)")
        analyse_sizes(groups, exp1_services, md, out_rows)
        for metric in EXP1_TESTED:
            analyse_repeated(groups, metric, exp1_services, EXP1_VARIANTS, args.alpha, out_rows, md)

    exp2_services = services_of(groups, EXP2_TESTED)
    if exp2_services:
        md.append("\n## Experiment 2 — build caching (instruction order × BuildKit cache mount)")
        for metric in EXP2_TESTED:
            analyse_repeated(groups, metric, exp2_services, EXP2_VARIANTS, args.alpha, out_rows, md)

    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "report.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(md) + "\n")
    fields = ["metric", "service", "variant", "test", "n", "mean", "sd", "median", "min", "max",
              "statistic", "p", "unit"]
    with open(os.path.join(args.out, "stats.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        for r in out_rows:
            w.writerow({k: r.get(k) for k in fields})
    print(f"wrote {os.path.join(args.out, 'report.md')} and stats.csv")


if __name__ == "__main__":
    main()
