#!/usr/bin/env python3
"""Statistical analysis of the container experiments.

Reads every measurements-*.csv written by experiments/container/measure-images.ps1
(columns: timestamp, metric, service, variant, run, value, unit, level) and writes

  report.md   descriptive statistics, normality and significance tests, size/time savings
  stats.csv   the same numbers in machine-readable form

Measurement Study 1, variants single / multi-jdk / multi-jre / multi (+ multi-jre-arena2 in load and soak):
  repeated metrics  -> Shapiro-Wilk per variant, Kruskal-Wallis across the variants,
                       pairwise Mann-Whitney U with Bonferroni correction as post-hoc test, Cliff's delta as effect size
                       (build/startup time, idle memory; every load_* and soak_* metric per concurrency level)
  concurrency curve -> medians per level and variant for the main load metrics
  image sizes       -> deterministic (one value per build), reported with % reduction per step

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
LOAD_VARIANTS = ["single", "multi-jdk", "multi-jre", "multi-jre-arena2", "multi"]
EXP1_TESTED = ["build_time", "startup_wall", "startup_ready", "memory_idle", "memory_idle_rss"]
# the concurrency curve table; every other load_*/soak_* metric is still tested per level
CURVE = ["load_throughput", "load_p99", "load_cpu_per_request", "load_rss_peak", "load_gc_pause_share",
         "load_gc_per_1k_requests", "load_alloc_per_request", "load_ttsp_p95"]
# of the NMT categories only the total and the memory outside it are tested
NMT_KEEP = ("nmt_total", "nmt_rss_unaccounted")
EXP1_SIZES = ["size_compressed", "size_uncompressed"]

LABELS = {
    "build_time": "Build time, cold (--no-cache)",
    "startup_wall": "Startup: docker run -> /actuator/health 200",
    "startup_ready": "Startup: JVM-reported application_ready_time",
    "memory_idle": "Memory at idle (docker stats, 30 s after healthy)",
    "memory_idle_rss": "Memory at idle (kernel RSS, 30 s after healthy)",
    "size_compressed": "Image size, compressed (registry)",
    "size_uncompressed": "Image size, uncompressed (layers)",
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


def group_name(service, level, gc=""):
    """Load and soak rows are compared per concurrency level (and GC, if one was set), so both become part of the group."""
    name = f"{service} @{level} users" if level else service
    return f"{name}, GC {gc}" if gc else name


def level_of(name):
    return int(name.rsplit("@", 1)[1].split()[0]) if "@" in name else -1


def sort_key(name):
    """service, then concurrency level, then GC"""
    return name.split(" @")[0], level_of(name), name.split(", GC ")[1] if ", GC " in name else ""


def grouped(rows):
    """{(metric, service[ @level], variant): ([values], unit)}"""
    groups = {}
    for r in rows:
        key = (r["metric"], group_name(r["service"], r.get("level") or "", r.get("gc") or ""), r["variant"])
        groups.setdefault(key, ([], r["unit"]))[0].append(r["value"])
    return groups


def label(metric):
    if metric in LABELS:
        return LABELS[metric]
    kind, _, rest = metric.partition("_")
    prefix = {"load": "Load test", "soak": "Long run"}.get(kind, kind)
    return f"{prefix}: {rest.replace('_', ' ')}"


def runtime_metrics(groups):
    """every load_*/soak_* metric present, NMT reduced to the total and the unaccounted part"""
    found = sorted({m for (m, _s, _v) in groups if m.startswith(("load_", "soak_"))})
    return [m for m in found if "_nmt_" not in m or m.endswith(NMT_KEEP)]


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


def cliffs_delta(a, b):
    """P(b > a) - P(b < a): -1..1, |d| < 0.147 negligible, < 0.33 small, < 0.474 medium, else large (Romano et al.)"""
    gt = sum(1 for x in a for y in b if y > x)
    lt = sum(1 for x in a for y in b if y < x)
    return (gt - lt) / (len(a) * len(b))


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
    return sorted({s for (m, s, _v) in groups if m in metrics}, key=sort_key)


def analyse_repeated(groups, metric, services, variants, alpha, out_rows, md):
    """Descriptive table + Shapiro-Wilk per variant + significance test per service."""
    present = [s for s in services if any((metric, s, v) in groups for v in variants)]
    if not present:
        return
    unit = next(groups[k][1] for k in groups if k[0] == metric)
    md.append(f"\n### {label(metric)} ({unit})\n")
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
                              f"median {vb} − {va}: {fmt(diff)} {unit} ({fmt(pct, 1)} %), "
                              f"Cliff's δ {fmt(cliffs_delta(a, b))}"])
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
                                  pval(p_adj), None if r is None else ("yes" if p_adj < alpha else "no"),
                                  f"Cliff's δ {fmt(cliffs_delta(samples[va], samples[vb]))}"])
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


def analyse_curve(groups, md):
    """medians per concurrency level (rows) and variant (columns): where do the images start to differ?"""
    for metric in CURVE:
        cells = {(s, v): statistics.median(vals) for (m, s, v), (vals, _u) in groups.items() if m == metric}
        if not cells:
            continue
        unit = next(u for (m, _s, _v), (_vals, u) in groups.items() if m == metric)
        variants = [v for v in LOAD_VARIANTS if any(k[1] == v for k in cells)]
        levels = sorted({s for s, _v in cells}, key=sort_key)
        md.append(f"\n#### {label(metric)} ({unit}), median per level\n")
        md.append(table(["level"] + variants, [[s] + [cells.get((s, v)) for v in variants] for s in levels]))


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
          "(Kruskal-Wallis / Mann-Whitney U), so they do not assume normality. Bonferroni corrects for the "
          "post-hoc pairs within one metric and level. With n = 5 per group the smallest two-sided Mann-Whitney p "
          "is 0.0079, with n = 10 it is 1.1e-5. Cliff's δ > 0 means the second variant has higher values."]

    exp1_services = [s for s in services_of(groups, EXP1_TESTED + EXP1_SIZES) if "@" not in s]
    if exp1_services:
        md.append("\n## Measurement Study 1 — image variants (single vs multi-jdk vs multi-jre vs multi)")
        analyse_sizes(groups, exp1_services, md, out_rows)
        for metric in EXP1_TESTED:
            analyse_repeated(groups, metric, exp1_services, EXP1_VARIANTS, args.alpha, out_rows, md)

    runtime = runtime_metrics(groups)
    if runtime:
        md.append("\n## Measurement Study 1 — runtime under load (concurrency series and long runs)")
        analyse_curve(groups, md)
        runtime_services = services_of(groups, runtime)
        for metric in runtime:
            analyse_repeated(groups, metric, runtime_services, LOAD_VARIANTS, args.alpha, out_rows, md)

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
