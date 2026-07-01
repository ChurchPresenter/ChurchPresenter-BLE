#!/usr/bin/env python3
"""
Extract compact 15-row training samples from a service session triple
(DB, detection-log, live-references).

Each sample is a 15-row window around one reference event, labelled by type:
  tp  — engine detected AND operator confirmed (True Positive)
  fp  — engine detected, operator NEVER confirmed (False Positive)
  fn  — operator confirmed, engine NEVER detected (False Negative)

Music rows (speech_type starts with "Music") are excluded from windows.

Usage:
    python tools/extract_training_samples.py \\
        --db    /path/to/2026-06-28_093118.db \\
        --dlog  /path/to/detection-log-2026-06-28_093118.jsonl \\
        --lref  /path/to/live-references-2026-06-28_10-12-22.jsonl \\
        --out   /path/to/training-samples-service4.jsonl \\
        --label service4
"""
import argparse, json, os, sqlite3, statistics, sys, io
from datetime import datetime, timezone

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

WINDOW_BEFORE = 12   # rows before the event anchor
WINDOW_AFTER  = 2    # rows after
MATCH_MS      = 90_000  # ±90 s window to pair a detection with a live-ref


# ── helpers ──────────────────────────────────────────────────────────────────

def parse_iso(s: str | None) -> float | None:
    """ISO 8601 timestamp → epoch ms, or None."""
    if not s:
        return None
    try:
        return datetime.fromisoformat(s.strip().replace("Z", "+00:00")).timestamp() * 1000.0
    except Exception:
        return None


def parse_db_ts(s: str | None) -> float | None:
    """'YYYY-MM-DD HH:mm:ss' (UTC) → epoch ms, or None."""
    if not s:
        return None
    try:
        return datetime.strptime(s.strip(), "%Y-%m-%d %H:%M:%S").replace(
            tzinfo=timezone.utc
        ).timestamp() * 1000.0
    except Exception:
        return None


def load_jsonl(path: str) -> list[dict]:
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


def load_db(path: str) -> list:
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    rows = list(conn.execute(
        "SELECT id, timestamp, text, translated_text, speech_type "
        "FROM transcriptions ORDER BY id"
    ))
    conn.close()
    return rows


def build_ts_index(rows: list) -> list[tuple[float, int]]:
    """Returns sorted [(epoch_ms, row_id)] for rows with parseable timestamps."""
    idx = []
    for r in rows:
        ts = parse_db_ts(r["timestamp"])
        if ts is not None:
            idx.append((ts, r["id"]))
    return sorted(idx)


def nearest_row_id(ts_ms: float, ts_index: list[tuple[float, int]]) -> int | None:
    """Binary search for the DB row whose timestamp is closest to ts_ms."""
    if not ts_index:
        return None
    lo, hi = 0, len(ts_index) - 1
    best_id, best_diff = ts_index[0][1], abs(ts_index[0][0] - ts_ms)
    while lo <= hi:
        mid = (lo + hi) // 2
        diff = abs(ts_index[mid][0] - ts_ms)
        if diff < best_diff:
            best_diff, best_id = diff, ts_index[mid][1]
        if ts_index[mid][0] < ts_ms:
            lo = mid + 1
        else:
            hi = mid - 1
    return best_id


def extract_window(rows_by_id: dict, anchor_id: int, sermon_ids: list[int]) -> list[dict]:
    """WINDOW_BEFORE rows before anchor + anchor + WINDOW_AFTER rows after, Music filtered."""
    sermon_set = set(sermon_ids)
    if anchor_id not in sermon_set:
        # anchor itself is Music — find nearest sermon row
        if not sermon_ids:
            return []
        anchor_id = min(sermon_ids, key=lambda x: abs(x - anchor_id))
    idx = sermon_ids.index(anchor_id)
    lo = max(0, idx - WINDOW_BEFORE)
    hi = min(len(sermon_ids), idx + WINDOW_AFTER + 1)
    return [
        {
            "id": sermon_ids[k],
            "text": rows_by_id[sermon_ids[k]]["text"],
            "translated": rows_by_id[sermon_ids[k]]["translated_text"],
        }
        for k in range(lo, hi)
        if sermon_ids[k] in rows_by_id
    ]


# ── main ─────────────────────────────────────────────────────────────────────

def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db",    required=True, help="Path to service .db file")
    ap.add_argument("--dlog",  required=True, help="Path to detection-log-*.jsonl")
    ap.add_argument("--lref",  required=True, help="Path to live-references-*.jsonl")
    ap.add_argument("--out",   required=True, help="Output path for training-samples-*.jsonl")
    ap.add_argument("--label", default="",    help="Session label (defaults to db filename stem)")
    args = ap.parse_args()

    label = args.label or os.path.basename(args.db).replace(".db", "")

    print(f"Loading DB: {args.db}")
    db_rows = load_db(args.db)
    rows_by_id: dict[int, object] = {r["id"]: r for r in db_rows}
    sermon_ids: list[int] = sorted(
        r["id"] for r in db_rows
        if not (r["speech_type"] or "").lower().startswith("music")
    )
    ts_index = build_ts_index(db_rows)
    print(f"  {len(db_rows)} total rows, {len(sermon_ids)} non-Music sermon rows")

    print(f"Loading detection-log: {args.dlog}")
    detections = [d for d in load_jsonl(args.dlog) if d.get("type") != "session"]
    print(f"  {len(detections)} detections")

    print(f"Loading live-references: {args.lref}")
    live_refs = [l for l in load_jsonl(args.lref) if l.get("type") != "session"]
    print(f"  {len(live_refs)} live-reference events")

    # Index live refs: (book, ch, v) → [ts_ms, ...]
    live_by_ref: dict[tuple, list[float]] = {}
    for l in live_refs:
        key = (l.get("book"), l.get("chapter"), l.get("verseStart"))
        live_by_ref.setdefault(key, []).append(float(l.get("ts_ms") or 0))

    out_samples: list[dict] = []

    # ── TP and FP from detection-log ─────────────────────────────────────────
    confirmed_keys: set[tuple] = set()
    for d in detections:
        det_ts = parse_iso(d.get("ts"))
        key = (d.get("book"), d.get("chapter"), d.get("verseStart"))
        live_times = live_by_ref.get(key, [])

        confirmed = False
        best_lt: float | None = None
        for lt in live_times:
            if det_ts is None or abs(lt - det_ts) <= MATCH_MS:
                if not confirmed or (det_ts and abs(lt - det_ts) < abs(best_lt - det_ts)):
                    best_lt = lt
                confirmed = True

        anchor = nearest_row_id(det_ts, ts_index) if det_ts else None
        window = extract_window(rows_by_id, anchor, sermon_ids) if anchor else []

        detected_ref = {
            "book": d.get("book"), "ch": d.get("chapter"),
            "v": d.get("verseStart"), "vEnd": d.get("verseEnd"),
            "tier": d.get("tier"), "src": d.get("source"),
        }

        if confirmed:
            confirmed_keys.add(key)
            latency_ms = int(best_lt - det_ts) if (best_lt and det_ts) else None
            out_samples.append({
                "session_id": label, "event_type": "tp",
                "rows": window,
                "expected_ref": {"book": key[0], "ch": key[1], "v": key[2]},
                "detected_ref": detected_ref,
                "latency_ms": latency_ms,
            })
        else:
            out_samples.append({
                "session_id": label, "event_type": "fp",
                "rows": window,
                "expected_ref": None,
                "detected_ref": detected_ref,
                "latency_ms": None,
            })

    # ── FN from live-references with no matching detection ───────────────────
    for l in live_refs:
        lt_ms = float(l.get("ts_ms") or 0)
        key = (l.get("book"), l.get("chapter"), l.get("verseStart"))

        already_matched = key in confirmed_keys and any(
            (d.get("book"), d.get("chapter"), d.get("verseStart")) == key
            and (parse_iso(d.get("ts")) is None or abs(parse_iso(d.get("ts")) - lt_ms) <= MATCH_MS)
            for d in detections
        )
        if already_matched:
            continue

        anchor = nearest_row_id(lt_ms, ts_index) if lt_ms else None
        window = extract_window(rows_by_id, anchor, sermon_ids) if anchor else []
        out_samples.append({
            "session_id": label, "event_type": "fn",
            "rows": window,
            "expected_ref": {"book": key[0], "ch": key[1], "v": key[2]},
            "detected_ref": None,
            "latency_ms": None,
        })

    # Sort: fn first (most actionable), then fp, then tp
    order = {"fn": 0, "fp": 1, "tp": 2}
    out_samples.sort(key=lambda x: order.get(x["event_type"], 9))

    with open(args.out, "w", encoding="utf-8") as f:
        for s in out_samples:
            f.write(json.dumps(s, ensure_ascii=False) + "\n")

    tp = sum(1 for s in out_samples if s["event_type"] == "tp")
    fp = sum(1 for s in out_samples if s["event_type"] == "fp")
    fn = sum(1 for s in out_samples if s["event_type"] == "fn")
    prec = tp / (tp + fp) if (tp + fp) else 0.0
    rec  = tp / (tp + fn) if (tp + fn) else 0.0
    lats = [s["latency_ms"] for s in out_samples if s["event_type"] == "tp" and s["latency_ms"] is not None]
    med_lat = f"{statistics.median(lats)/1000:+.1f}s" if lats else "n/a"

    print(f"\nWrote {len(out_samples)} samples → {args.out}")
    print(f"  TP={tp}  FP={fp}  FN={fn}")
    print(f"  Precision={prec:.1%}  Recall={rec:.1%}  Median latency={med_lat}")
    print("  (negative latency = engine was ahead of operator — good)")


if __name__ == "__main__":
    main()
