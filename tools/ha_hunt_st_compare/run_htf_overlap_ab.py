#!/usr/bin/env python3
"""HTF M45 overlap/squeeze filter A/B. Same sim / window as PR #144.

Locked: half+BE+HA, slow 144, gate ON, flat, ST 7/2, pyramid OFF.
Primary: block_htf_band_overlap OFF vs ON.
Secondary: entry-TF band_cross_strict OFF vs ON (overlap OFF).
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from tools.ha_hunt_st_compare.ohlc import load_symbol, resample_bars, series_note
from tools.ha_hunt_st_compare.simulator import Book, Params, _ha_exit_locked, simulate

DOCS = ROOT / "docs"
OUT_MD = DOCS / "ha-hunt-htf-overlap-ab-12mo.md"
OUT_JSON = DOCS / "ha-hunt-htf-overlap-ab-12mo.json"

START = datetime(2025, 9, 11, tzinfo=timezone.utc)
END = datetime(2026, 9, 11, tzinfo=timezone.utc)

# Same stacks Adam asked for. US500 M5+M45 is cheap once M5 is loaded.
CELLS = [
    ("XAU", "M5+M45", 5, 45),
    ("US100", "M5+M45", 5, 45),
    ("US100", "M15+H1", 15, 60),
    ("US500", "M15+H1", 15, 60),
    ("US500", "M5+M45", 5, 45),
]


def locked_st72(
    chart_minutes: int,
    st_tf_minutes: int,
    *,
    block_htf_band_overlap: bool = False,
    band_cross_strict: bool = False,
) -> Params:
    ov = "on" if block_htf_band_overlap else "off"
    st = "on" if band_cross_strict else "off"
    return _ha_exit_locked(
        name=f"st7x2_flat_m{chart_minutes}_st{st_tf_minutes}_ovl{ov}_strict{st}",
        st_atr_len=7,
        st_factor=2.0,
        allow_pyramid=False,
        chart_minutes=chart_minutes,
        st_tf_minutes=st_tf_minutes,
        block_htf_band_overlap=block_htf_band_overlap,
        band_cross_strict=band_cross_strict,
    )


def _fmt_pf(pf: float) -> str:
    if pf == float("inf"):
        return "∞"
    return f"{pf:.2f}"


def _md_table(headers: list[str], rows: list[list[str]]) -> str:
    align = []
    for h in headers:
        align.append("---:" if h.lower() not in {"stack", "symbol", "filter", "mode", "call"} else "---")
    lines = ["| " + " | ".join(headers) + " |", "| " + " | ".join(align) + " |"]
    for r in rows:
        lines.append("| " + " | ".join(r) + " |")
    return "\n".join(lines)


def _book_row(b: Book, **extra) -> dict:
    row = {
        "symbol": b.symbol,
        "variant": b.variant,
        "n": b.n,
        "wr_pct": round(b.wr_pct, 2),
        "sum_r": round(b.sum_r, 3),
        "avg_r": round(b.avg_r, 4),
        "max_dd_r": round(b.max_dd_r, 3),
        "pf": None if b.pf == float("inf") else round(b.pf, 3),
        "n_tp1": b.n_tp1,
        "n_long": b.n_long,
        "n_short": b.n_short,
        "exits": b.exits,
    }
    row.update(extra)
    return row


def _metrics_cells(b: Book) -> list[str]:
    return [
        str(b.n),
        f"{b.wr_pct:.1f}",
        f"{b.sum_r:+.1f}",
        _fmt_pf(b.pf),
        f"{b.max_dd_r:.1f}",
    ]


def _delta(on: Book, off: Book) -> list[str]:
    return [
        f"{on.n - off.n:+d}",
        f"{on.wr_pct - off.wr_pct:+.1f}",
        f"{on.sum_r - off.sum_r:+.1f}",
        f"{(0.0 if on.pf == float('inf') else on.pf) - (0.0 if off.pf == float('inf') else off.pf):+.2f}",
        f"{on.max_dd_r - off.max_dd_r:+.1f}",
    ]


def decide_call(pairs: list[tuple[str, str, Book, Book]]) -> tuple[str, str]:
    """Does HTF overlap ON help vs OFF?

    Help = higher combined sumR, or (same sumR and better WR / lower DD).
    Mixed cells are called out. Default stays OFF unless the book improves.
    """
    if not pairs:
        return "INCONCLUSIVE", "no completed cells"
    better_sum = 0
    worse_sum = 0
    better_wr = 0
    worse_wr = 0
    comb_off = 0.0
    comb_on = 0.0
    bits = []
    for sym, stack, off, on in pairs:
        comb_off += off.sum_r
        comb_on += on.sum_r
        d = on.sum_r - off.sum_r
        bits.append(f"{sym} {stack} {d:+.1f}R (n {off.n}→{on.n}, WR {off.wr_pct:.1f}→{on.wr_pct:.1f})")
        if d > 0.25:
            better_sum += 1
        elif d < -0.25:
            worse_sum += 1
        if on.wr_pct > off.wr_pct + 0.25:
            better_wr += 1
        elif on.wr_pct < off.wr_pct - 0.25:
            worse_wr += 1
    d_comb = comb_on - comb_off
    detail = "; ".join(bits)
    if better_sum > 0 and worse_sum == 0 and d_comb > 0:
        return (
            "YES — turn ON",
            f"every cell's sumR improved (combined {d_comb:+.1f}R). {detail}",
        )
    if worse_sum > 0 and better_sum == 0 and d_comb < 0:
        return (
            "NO — keep OFF",
            f"every cell's sumR fell (combined {d_comb:+.1f}R). WR lift {better_wr}/{len(pairs)} cells. {detail}",
        )
    if d_comb > 2.0 and better_sum >= worse_sum:
        return (
            "LEAN YES — optional ON",
            f"combined sumR {d_comb:+.1f}R with mixed cells ({better_sum} up / {worse_sum} down). {detail}",
        )
    if better_wr > 0 and worse_sum >= better_sum and d_comb <= 0:
        return (
            "NO — keep OFF",
            f"WR rose on {better_wr}/{len(pairs)} cells but combined sumR {d_comb:+.1f}R "
            f"(n cut; hypothesis: WR up, n down, sumR not better). {detail}",
        )
    return (
        "NO — keep OFF (default)",
        f"mixed / no clear book lift (combined {d_comb:+.1f}R; {better_sum} up / {worse_sum} down). {detail}",
    )


def write_report(
    pairs: list[tuple[str, str, Book, Book]],
    strict_pairs: list[tuple[str, str, Book, Book]],
    results: list[dict],
    load_errors: dict[str, str],
    sources: dict[str, str],
    call: str,
    why: str,
) -> None:
    DOCS.mkdir(parents=True, exist_ok=True)
    headers = ["symbol", "stack", "filter", "n", "WR%", "sumR", "PF", "DD"]
    primary_rows: list[list[str]] = []
    delta_rows: list[list[str]] = []
    for sym, stack, off, on in pairs:
        primary_rows.append([sym, stack, "OFF (lock)", *_metrics_cells(off)])
        primary_rows.append([sym, stack, "ON", *_metrics_cells(on)])
        delta_rows.append([sym, stack, "ON − OFF", *_delta(on, off)])

    sec_rows: list[list[str]] = []
    for sym, stack, loose, strict in strict_pairs:
        sec_rows.append([sym, stack, "loose (lock)", *_metrics_cells(loose)])
        sec_rows.append([sym, stack, "strict ON", *_metrics_cells(strict)])

    src_lines = [f"- **{sym}**: {note}" for sym, note in sources.items()]
    err_lines = [f"- **{sym}**: `{err}`" for sym, err in load_errors.items()]

    md = f"""# HA-Hunt ST v3 — HTF M45 overlap / squeeze filter A/B (~12m)

Research only. **Do not merge as a default-ON change.** Pine switch stays **OFF** unless
the book clearly improves. Same simulator, window, and locked stack as PR #144.

Locked: half TP1 @ 1:2 → BE → HA flip. Slow 144, fast 33, M45 structure gate ON,
capReg 2, **flat only**, **ST 7/2**, pyramid OFF, entry-TF `bandCrossStrict` OFF
(unless the secondary table).

Window: `{START.date()}` → `{END.date()}` UTC. Not Capital mid unless credentials
were present (then preferred).

## Call

**{call}**

{why}

Hypothesis check: blocking HTF squeeze *may* lift WR but cut n; that does **not**
automatically lift sumR. Default stays OFF unless sumR improves.

## Part A — HTF overlap filter OFF vs ON (ST 7/2)

{_md_table(headers, primary_rows)}

### Deltas (ON − OFF)

{_md_table(["symbol", "stack", "filter", "Δn", "ΔWR", "ΔsumR", "ΔPF", "ΔDD"], delta_rows)}

Overlap = last closed M45 fast+slow RMA ribbons intersect (not clear either side).
Long clear = `m45FLo > m45SUp`. Short clear = `m45FUp < m45SLo`. When ON, both
sides are skipped while overlapping. `reqM45Struct` is still stack OR leave —
not this skip.

## Part B — entry-TF `bandCrossStrict` OFF vs ON (overlap OFF, ST 7/2)

Secondary. Entry-TF fast band must be fully clear of slow (M5 or M15). Does not
look at M45.

{_md_table(headers, sec_rows) if sec_rows else "_no secondary cells_"}

## Series

{chr(10).join(src_lines) if src_lines else "_none_"}

{("## Load errors" + chr(10) + chr(10) + chr(10).join(err_lines)) if err_lines else ""}

Rerun: `python3 -m tools.ha_hunt_st_compare.run_htf_overlap_ab`
"""
    OUT_MD.write_text(md.strip() + "\n", encoding="utf-8")
    OUT_JSON.write_text(
        json.dumps(
            {
                "window": {"start": START.isoformat(), "end": END.isoformat()},
                "lock": {
                    "st": "7/2",
                    "mgmt": "half+BE+HA",
                    "slow": 144,
                    "gate": True,
                    "flat": True,
                    "pyramid": False,
                },
                "call": call,
                "why": why,
                "results": results,
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    print(f"wrote {OUT_MD}", flush=True)
    print(f"wrote {OUT_JSON}", flush=True)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="HTF M45 overlap A/B (ST 7/2, same sim as #144)")
    ap.add_argument("--skip-strict", action="store_true", help="Skip entry-TF bandCrossStrict A/B")
    args = ap.parse_args(argv)

    print(f"window {START.isoformat()} → {END.isoformat()}", flush=True)
    print("pyramid OFF · half+BE+HA · slow 144 · gate ON · flat · ST 7/2", flush=True)

    need = sorted({sym for sym, *_ in CELLS})
    bars = {}
    load_errors: dict[str, str] = {}
    sources: dict[str, str] = {}
    for sym in need:
        print(f"load {sym}… {series_note(sym)}", flush=True)
        try:
            bars[sym] = load_symbol(sym, START, END, prefer_capital=True)
            m = bars[sym].meta
            sources[sym] = f"{m.source}  n_m5={m.n_m5}  {m.first} → {m.last}  ({series_note(sym)})"
            print(f"  {m.source}  n_m5={m.n_m5}  {m.first} → {m.last}", flush=True)
        except Exception as e:
            load_errors[sym] = str(e)
            print(f"  FAIL {e}", flush=True)

    if "XAU" not in bars or "US100" not in bars:
        print("required symbols XAU/US100 failed", file=sys.stderr)
        write_report([], [], [], load_errors, sources, "INCONCLUSIVE", "required symbols failed to load")
        return 1

    charts: dict[tuple[str, int], object] = {}

    def chart_for(sym: str, minutes: int):
        key = (sym, minutes)
        if key not in charts:
            src = bars[sym]
            charts[key] = src if minutes == 5 else resample_bars(src, minutes)
        return charts[key]

    results: list[dict] = []
    pairs: list[tuple[str, str, Book, Book]] = []
    strict_pairs: list[tuple[str, str, Book, Book]] = []

    for sym, stack, chart_min, st_min in CELLS:
        if sym not in bars:
            print(f"skip {sym} {stack} (no data)", flush=True)
            continue
        chart = chart_for(sym, chart_min)
        print(f"{sym} {stack}  bars={len(chart.close)}", flush=True)
        off = simulate(sym, chart, locked_st72(chart_min, st_min, block_htf_band_overlap=False))
        on = simulate(sym, chart, locked_st72(chart_min, st_min, block_htf_band_overlap=True))
        pairs.append((sym, stack, off, on))
        results.append(_book_row(off, part="A", stack=stack, filter="overlap_off"))
        results.append(_book_row(on, part="A", stack=stack, filter="overlap_on"))
        print(
            f"  ovl OFF  n={off.n:4}  WR={off.wr_pct:5.1f}%  sumR={off.sum_r:8.2f}  "
            f"PF={_fmt_pf(off.pf)}  DD={off.max_dd_r:6.2f}",
            flush=True,
        )
        print(
            f"  ovl ON   n={on.n:4}  WR={on.wr_pct:5.1f}%  sumR={on.sum_r:8.2f}  "
            f"PF={_fmt_pf(on.pf)}  DD={on.max_dd_r:6.2f}",
            flush=True,
        )
        if not args.skip_strict:
            strict = simulate(
                sym,
                chart,
                locked_st72(chart_min, st_min, block_htf_band_overlap=False, band_cross_strict=True),
            )
            strict_pairs.append((sym, stack, off, strict))
            results.append(_book_row(strict, part="B", stack=stack, filter="strict_on"))
            print(
                f"  strict   n={strict.n:4}  WR={strict.wr_pct:5.1f}%  sumR={strict.sum_r:8.2f}  "
                f"PF={_fmt_pf(strict.pf)}  DD={strict.max_dd_r:6.2f}",
                flush=True,
            )

    call, why = decide_call(pairs)
    print(f"CALL: {call}", flush=True)
    print(why, flush=True)
    write_report(pairs, strict_pairs, results, load_errors, sources, call, why)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
