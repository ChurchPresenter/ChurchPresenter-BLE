#!/usr/bin/env python3
"""
Minimal triage report from detection-log + live-references. No DB needed.

Anchors on each operator go-live event and classifies what the engine did in the
[-90s, +5s] window around it:

  TP        — engine fired the correct ref in the window
  PREMATURE — engine fired the right book+chapter but wrong verse first, then corrected
  FN        — engine missed the ref entirely (or fired the wrong book/chapter only)
  FP        — wrong engine detections inside a live-ref window

Everything outside all live-ref windows is ignored, so announcements, prayers, and
pre/post-service noise never inflate the counts.

Usage:
    python tools/triage_report.py \\
        --dlog detection-log-2026-06-28_170555.jsonl \\
        --lref live-references-2026-06-28_18-09-05.jsonl \\
        [--label service6]  [--window-before 90]  [--window-after 5]
"""
import argparse, json, sys, io, statistics
from datetime import datetime, timezone

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

WINDOW_BEFORE_S = 90
WINDOW_AFTER_S  = 5


# ── helpers ──────────────────────────────────────────────────────────────────

def parse_iso(s):
    if not s:
        return None
    try:
        return datetime.fromisoformat(s.strip().replace("Z", "+00:00")).timestamp() * 1000.0
    except Exception:
        return None

def load_jsonl(path):
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError:
                    pass
    return rows

def ref_str(book, ch, v, v_end=None):
    s = f"book={book} {ch}:{v}"
    if v_end:
        s += f"-{v_end}"
    return s

def fmt_s(ms):
    if ms is None:
        return "?"
    return f"{ms/1000:+.1f}s"


# ── main logic ────────────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dlog",  required=True, help="detection-log-*.jsonl path")
    ap.add_argument("--lref",  required=True, help="live-references-*.jsonl path")
    ap.add_argument("--label", default=None,  help="Session label for the header")
    ap.add_argument("--window-before", type=float, default=WINDOW_BEFORE_S,
                    help="Seconds before live-ref to look back (default 90)")
    ap.add_argument("--window-after",  type=float, default=WINDOW_AFTER_S,
                    help="Seconds after live-ref to look forward (default 5)")
    args = ap.parse_args()

    wb_ms = args.window_before * 1000
    wa_ms = args.window_after  * 1000

    label = args.label or args.lref.split("/")[-1].replace("live-references-", "").replace(".jsonl", "")

    raw_dets  = [d for d in load_jsonl(args.dlog) if d.get("type") != "session"]
    raw_lives = [l for l in load_jsonl(args.lref) if l.get("type") != "session"]

    # Build detections with parsed timestamps
    dets = []
    for d in raw_dets:
        ts = parse_iso(d.get("ts"))
        if ts is None:
            continue
        dets.append({
            "ts": ts,
            "book": d.get("book"), "ch": d.get("chapter"), "v": d.get("verseStart"),
            "tier": d.get("tier"), "src": d.get("source"),
            "transcript": (d.get("transcript") or "").strip(),
        })

    # Sort live-refs by timestamp
    lives = []
    for l in raw_lives:
        ts = float(l.get("ts_ms") or 0)
        if ts == 0:
            continue
        lives.append({
            "ts": ts,
            "book": l.get("book"), "ch": l.get("chapter"), "v": l.get("verseStart"),
            "auto": l.get("autoFollow", False),
        })
    lives.sort(key=lambda x: x["ts"])

    if not lives:
        print("No live-reference events found.")
        return

    tps, fns, fps, prematures = [], [], [], []
    latencies = []

    for live in lives:
        lt = live["ts"]
        b, c, v = live["book"], live["ch"], live["v"]

        # Detections in the window around this go-live event
        window = [d for d in dets if lt - wb_ms <= d["ts"] <= lt + wa_ms]

        # Split into: correct (exact match), same-chapter (potential premature), other (FP)
        correct = [d for d in window if d["book"] == b and d["ch"] == c and d["v"] == v]
        same_ch = [d for d in window if d["book"] == b and d["ch"] == c and d["v"] != v]
        wrong   = [d for d in window if not (d["book"] == b and d["ch"] == c)]

        if correct:
            # Find the earliest correct detection
            earliest_correct = min(correct, key=lambda d: d["ts"])
            lat = earliest_correct["ts"] - lt  # negative = engine ahead of operator
            latencies.append(lat)

            # Premature = a same-chapter wrong-verse detection that fired BEFORE the correct one
            pre = [d for d in same_ch if d["ts"] < earliest_correct["ts"]]
            if pre:
                earliest_pre = min(pre, key=lambda d: d["ts"])
                prematures.append({
                    "live": live, "correct": earliest_correct, "premature": earliest_pre,
                    "lat_correct": lat,
                    "lat_premature": earliest_pre["ts"] - lt,
                })
            else:
                tps.append({"live": live, "det": earliest_correct, "lat": lat})

            # FPs = wrong-book/chapter detections in window (engine was also noisy alongside correct)
            for d in wrong:
                fps.append({"live_ref": ref_str(b, c, v), "det": d})
        else:
            # Engine missed the correct ref
            fns.append({"live": live, "wrong_in_window": wrong + same_ch})
            # All detections in window (wrong book, wrong verse) are FPs
            for d in wrong + same_ch:
                fps.append({"live_ref": ref_str(b, c, v), "det": d})

    # ── output ────────────────────────────────────────────────────────────────

    total_events = len(lives)
    n_tp = len(tps) + len(prematures)  # premature = late-corrected TP
    n_fp = len(fps)
    n_fn = len(fns)
    n_pre = len(prematures)
    precision = n_tp / (n_tp + n_fp) if (n_tp + n_fp) else None
    recall    = n_tp / (n_tp + n_fn) if (n_tp + n_fn) else None
    med_lat   = statistics.median(latencies) if latencies else None

    def pct(v):
        return f"{v:.1%}" if v is not None else "—"

    print()
    print(f"=== {label}  events={total_events}  "
          f"Precision={pct(precision)}  Recall={pct(recall)}  "
          f"Premature={n_pre}  Med.latency={fmt_s(med_lat)} ===")
    print()

    if fns:
        print(f"MISSED ({len(fns)} FN):")
        for fn in fns:
            l = fn["live"]
            wrong = fn["wrong_in_window"]
            note = ""
            if wrong:
                ex = wrong[0]
                note = f" — engine fired {ref_str(ex['book'], ex['ch'], ex['v'])} instead"
                if ex["transcript"]:
                    note += f'  trigger: "{ex["transcript"][:80]}"'
            print(f"  {ref_str(l['book'], l['ch'], l['v'])}{note}")
        print()

    if prematures:
        print(f"PREMATURE ({len(prematures)}):")
        for p in prematures:
            l, pre, cor = p["live"], p["premature"], p["correct"]
            pre_lat = fmt_s(p["lat_premature"])
            cor_lat = fmt_s(p["lat_correct"])
            print(f"  {ref_str(l['book'], l['ch'], l['v'])}  "
                  f"— fired v={pre['v']} at {pre_lat}, corrected at {cor_lat}")
        print()

    if fps:
        # Deduplicate FPs by (book, ch, v) to keep output short
        seen = set()
        unique_fps = []
        for fp in fps:
            key = (fp["det"]["book"], fp["det"]["ch"], fp["det"]["v"])
            if key not in seen:
                seen.add(key)
                unique_fps.append(fp)
        print(f"WRONG ({len(fps)} FP, {len(unique_fps)} unique refs):")
        for fp in unique_fps[:20]:
            d = fp["det"]
            trigger = f'  trigger: "{d["transcript"][:80]}"' if d["transcript"] else ""
            print(f"  {ref_str(d['book'], d['ch'], d['v'])}  src={d['src']} tier={d['tier']}{trigger}")
        if len(unique_fps) > 20:
            print(f"  … and {len(unique_fps) - 20} more")
        print()

    if not fns and not prematures and not fps:
        print("All live-reference events detected correctly with no FPs or premature detections.")
        print()

    if latencies:
        early = sum(1 for l in latencies if l < 0)
        late  = sum(1 for l in latencies if l >= 0)
        print(f"Latency: {early} early (engine ahead of operator)  {late} late/same  "
              f"med={fmt_s(med_lat)}")
        print("  (negative = engine detected before operator went live — good)")
        print()


if __name__ == "__main__":
    main()
