#!/usr/bin/env python3
"""Last-quarter ST_V3 fixed-RR bakeoff (ATR 7 / factor 2.0, flat only).

Research only. Reuses ``tools.ha_hunt_st_compare`` (loader + simulator).
Does not change prod Java defaults. Does not invent prices.

Window: last ~90 calendar days ending 2026-09-13 (entries 2026-06-15 → 2026-09-13).
Warmup bars are loaded before the window so HTF RMA / Supertrend are live.
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from tools.ha_hunt_st_compare.ohlc import (
    EXTRA_SYMBOLS,
    PRIMARY_SYMBOLS,
    SERIES_NOTES,
    coverage_table,
    load_symbol,
    resample_bars,
    series_note,
)
from tools.ha_hunt_st_compare.simulator import (
    Book,
    _metrics,
    st_v3_fixed_rr,
    st_v3_prod_like,
    simulate,
)

DOCS = ROOT / "docs"
OUT_MD = DOCS / "st-v3-fixed-rr-q.md"
OUT_JSON = DOCS / "st-v3-fixed-rr-q.json"

# Last ~90 calendar days ending ~2026-09-13.
TRADE_END = datetime(2026, 9, 13, tzinfo=timezone.utc)
TRADE_START = TRADE_END - timedelta(days=90)  # 2026-06-15
WARMUP_DAYS = 120
FETCH_START = TRADE_START - timedelta(days=WARMUP_DAYS)

STACKS = [
    ("M5_ST_V3", "M5+M45", 5, 45, True),
    ("M15_ST_V3", "M15+H1", 15, 60, True),
    ("H1_ST_V3", "H1+H4", 60, 240, True),
]

ENTRY_MODES = [
    ("A", "band_st", "Band-cross + ST bias (ST_V3 entry)"),
    ("B", "st_flip", "Supertrend flip only"),
    ("C", "band_only", "Band-cross, no ST-agree filter"),
]

RR_TARGETS = [1.0, 1.5]

TRADE_START_ISO = TRADE_START.strftime("%Y-%m-%dT%H:%M:%S")
TRADE_END_ISO = TRADE_END.strftime("%Y-%m-%dT%H:%M:%S")


def _fmt_pf(pf: float) -> str:
    if pf == float("inf"):
        return "∞"
    return f"{pf:.2f}"


def _md_table(headers: list[str], rows: list[list[str]]) -> str:
    align = []
    for h in headers:
        numeric = h.lower() not in {
            "stack",
            "symbol",
            "entry",
            "mode",
            "rr",
            "source",
            "note",
            "cell",
            "call",
            "variant",
            "hits 50%",
        }
        align.append("---:" if numeric else "---")
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


def _brief(b: Book) -> str:
    return f"n={b.n} WR {b.wr_pct:.1f}% sumR {b.sum_r:+.1f} avgR {b.avg_r:.3f} PF {_fmt_pf(b.pf)} DD {b.max_dd_r:.1f}"


def _worth_cell(b: Book) -> str:
    if b.n < 8:
        return "thin"
    pf = 0.0 if b.pf == float("inf") else b.pf
    if b.wr_pct >= 50.0 and b.sum_r > 0 and pf >= 1.05:
        return "hits ~50%+ WR and +EV"
    if b.wr_pct >= 50.0 and b.sum_r <= 0:
        return "hits ~50%+ WR but −EV"
    if b.sum_r > 0 and pf >= 1.10:
        return "+EV, WR below 50%"
    if b.sum_r > 0:
        return "marginal +EV"
    return "−EV"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="ST_V3 last-quarter fixed-RR bakeoff")
    ap.add_argument("--symbols", default="", help="comma list (default: primary + extra)")
    ap.add_argument("--no-extra", action="store_true", help="skip XAG/J225/USDJPY")
    ap.add_argument("--no-h1", action="store_true", help="skip H1+H4 stack")
    ap.add_argument("--no-c", action="store_true", help="skip optional band-only (C) cells")
    args = ap.parse_args(argv)

    DOCS.mkdir(parents=True, exist_ok=True)
    want = PRIMARY_SYMBOLS[:]
    if not args.no_extra:
        want += EXTRA_SYMBOLS
    if args.symbols.strip():
        want = [s.strip().upper() for s in args.symbols.split(",") if s.strip()]
        want = ["BTC" if s == "BTCUSD" else s for s in want]

    stacks = [s for s in STACKS if not (args.no_h1 and s[0] == "H1_ST_V3")]
    entries = [e for e in ENTRY_MODES if not (args.no_c and e[0] == "C")]

    print(
        f"window entries {TRADE_START_ISO} → {TRADE_END_ISO}  "
        f"fetch {FETCH_START.date()} (warmup {WARMUP_DAYS}d)",
        flush=True,
    )
    print(
        "locked ST 7/2.0 · flat · stop=ST line · no half/BE/runner on RR cells",
        flush=True,
    )

    bars = {}
    load_errors = {}
    for sym in want:
        print(f"load {sym}… {series_note(sym)}", flush=True)
        try:
            bars[sym] = load_symbol(sym, FETCH_START, TRADE_END, prefer_capital=True)
            m = bars[sym].meta
            print(
                f"  {m.source}  n_m5={m.n_m5}  n_m1={m.n_m1}  {m.first} → {m.last}",
                flush=True,
            )
        except Exception as e:
            load_errors[sym] = str(e)
            print(f"  FAIL {e}", flush=True)

    if not bars:
        print("no symbols loaded", file=sys.stderr)
        return 1

    charts: dict[tuple[str, int], object] = {}

    def chart_for(sym: str, minutes: int):
        key = (sym, minutes)
        if key not in charts:
            src = bars[sym]
            charts[key] = src if minutes == 5 else resample_bars(src, minutes)
        return charts[key]

    results: list[dict] = []
    books: dict[tuple[str, str, str, float], Book] = {}
    prod_books: dict[tuple[str, str], Book] = {}

    for stack_id, stack, chart_min, st_min, _ok in stacks:
        for sym in want:
            if sym not in bars:
                continue
            chart = chart_for(sym, chart_min)
            print(f"{stack_id} {sym} bars={len(chart.close)}", flush=True)
            for mode_tag, mode, _label in entries:
                for rr in RR_TARGETS:
                    p = st_v3_fixed_rr(
                        mode, rr, chart_min, st_min, TRADE_START_ISO, TRADE_END_ISO
                    )
                    book = simulate(sym, chart, p)
                    books[(sym, stack_id, mode_tag, rr)] = book
                    results.append(
                        _book_row(
                            book,
                            part="fixed_rr",
                            stack=stack_id,
                            stack_label=stack,
                            entry_mode=mode,
                            entry_tag=mode_tag,
                            rr=rr,
                        )
                    )
                    print(
                        f"  {mode_tag} 1:{rr:g}  n={book.n:4}  WR={book.wr_pct:5.1f}%  "
                        f"sumR={book.sum_r:8.2f}  PF={_fmt_pf(book.pf)}  DD={book.max_dd_r:6.2f}",
                        flush=True,
                    )
            if stack_id == "M15_ST_V3":
                p_prod = st_v3_prod_like(chart_min, st_min, TRADE_START_ISO, TRADE_END_ISO)
                prod = simulate(sym, chart, p_prod)
                prod_books[(sym, stack_id)] = prod
                results.append(
                    _book_row(
                        prod,
                        part="prod_like",
                        stack=stack_id,
                        stack_label=stack,
                        entry_mode="band_st",
                        entry_tag="prod",
                        rr=2.0,
                    )
                )
                print(
                    f"  prod-like  n={prod.n:4}  WR={prod.wr_pct:5.1f}%  "
                    f"sumR={prod.sum_r:8.2f}  PF={_fmt_pf(prod.pf)}",
                    flush=True,
                )

    cov = coverage_table(bars)
    generated = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")

    # ---- report ----
    lines = [
        "# ST_V3 last-quarter fixed-RR bakeoff",
        "",
        f"Generated **{generated}**. Research note — **do not merge as live Java**.",
        "",
        "## Window and data",
        "",
        f"- **Trade window (entries):** `{TRADE_START.date()}` → `{TRADE_END.date()}` "
        f"({(TRADE_END - TRADE_START).days} calendar days, ending ~2026-09-13).",
        f"- **Fetch / warmup:** `{FETCH_START.date()}` → `{TRADE_END.date()}` "
        f"({WARMUP_DAYS} calendar days before first entry so HTF RMA144 / ST 7/2.0 are live).",
        "- **Data source order** (same as prior `ha_hunt_st_compare` bakeoffs): "
        "local `cache/*_M5.csv` → Capital DEMO mids (if `CAPITAL_*` env is set) → "
        "HistData M1 resampled to M5 → Dukascopy M1 resampled to M5 → "
        "Coinbase Exchange 5m (BTC). Never invents prices.",
        "- Loader notes per symbol are in the coverage table. HistData stamps are "
        "US Eastern → UTC; Dukascopy / Coinbase are UTC native.",
        "",
        "## Locked stack (current ST_V3, management replaced on RR cells)",
        "",
        "- Supertrend **ATR 7 / factor 2.0** (PR #146 / #150).",
        "- Stop = Supertrend line; **1R = |entry − ST|**. Invalid (wrong-side) ST stops skipped.",
        "- Flat only (no pyramid). Cap 2 fills / ST regime.",
        "- Fast RMA 33 / slow 144. HTF structure gate ON for band-cross cells "
        "(same HTF as the Supertrend — Java `StV3Engine`, not the older pine M45-always gate).",
        "- **Fixed-RR cells:** full close at +1.0R or +1.5R. No half TP1, no BE runner, "
        "no HA / band runner. Still flatten if the original ST stop is hit first "
        "(stop wins on a dual-hit bar).",
        "- **Prod-like baseline:** half @ 1:2 → stop to BE → entry-TF **band-cross** runner "
        "(PR #150 live `stV3Exit`). Labeled `prod-like`. Required compare: "
        "M15+H1 on XAU and US100; the same cell is also printed for every loaded symbol.",
        "",
        "### Entry modes",
        "",
        "- **A — band-cross + ST bias:** first entry-TF close beyond the fast band, "
        "direction must match HTF Supertrend (current ST_V3 trigger).",
        "- **B — Supertrend flip only:** enter on the chart bar where the closed HTF "
        "Supertrend prints a new direction; no band-cross required.",
        "- **C — band-cross without ST agree:** same band-cross as A, ST direction not "
        "required. With a strict ST-line stop this is usually identical to A "
        "(against-ST entries have the line on the wrong side and are skipped).",
        "",
        "## Coverage",
        "",
    ]

    cov_rows = []
    for row in cov:
        cov_rows.append(
            [
                row["symbol"],
                row["source"],
                str(row["n_m5"]),
                str(row["n_m1"]),
                f"{row['coverage_days']:.1f}",
                row["first"][:19],
                row["last"][:19],
                (row["note"] or SERIES_NOTES.get(row["symbol"], ""))[:120],
            ]
        )
    lines += [
        _md_table(
            ["symbol", "source", "n_M5", "n_M1", "days", "first", "last", "note"],
            cov_rows,
        ),
        "",
    ]
    if load_errors:
        lines += ["### Load failures", ""]
        for sym, err in load_errors.items():
            lines.append(f"- **{sym}:** `{err}`")
        lines.append("")

    # Master matrix
    lines += [
        "## Master matrix — symbol × stack × entry × RR",
        "",
        "WR% is R>0 on the booked (full-size) R. maxDD is peak-to-trough of the R equity, in R.",
        "",
    ]
    master = []
    for stack_id, stack, *_rest in stacks:
        for sym in want:
            for mode_tag, _mode, _label in entries:
                for rr in RR_TARGETS:
                    b = books.get((sym, stack_id, mode_tag, rr))
                    if b is None:
                        continue
                    master.append(
                        [
                            sym,
                            stack,
                            f"{mode_tag}",
                            f"1:{rr:g}",
                            str(b.n),
                            f"{b.wr_pct:.1f}",
                            f"{b.sum_r:+.2f}",
                            _fmt_pf(b.pf),
                            f"{b.max_dd_r:.2f}",
                            f"{b.avg_r:.3f}",
                            _worth_cell(b),
                        ]
                    )
    lines += [
        _md_table(
            ["symbol", "stack", "entry", "RR", "n", "WR%", "sumR", "PF", "maxDD", "avgR", "note"],
            master,
        ),
        "",
    ]

    # Cells at ~50% WR
    hit50 = [
        row
        for row in master
        if row[5] not in {"—"} and float(row[5]) >= 50.0 and int(row[4]) >= 8
    ]
    lines += [
        "## Cells with WR ≥ 50% (n ≥ 8)",
        "",
    ]
    if hit50:
        lines += [
            _md_table(
                ["symbol", "stack", "entry", "RR", "n", "WR%", "sumR", "PF", "maxDD", "avgR", "note"],
                hit50,
            ),
            "",
        ]
    else:
        lines += ["No cell with n ≥ 8 printed a win rate of 50% or higher.", ""]

    # Book totals per stack × entry × RR
    lines += [
        "## Book totals (summed across loaded symbols)",
        "",
    ]
    tot_rows = []
    totals: dict[tuple[str, str, float], list[Book]] = {}
    for (sym, stack_id, mode_tag, rr), b in books.items():
        totals.setdefault((stack_id, mode_tag, rr), []).append(b)
    for stack_id, stack, *_r in stacks:
        for mode_tag, _mode, label in entries:
            for rr in RR_TARGETS:
                group = totals.get((stack_id, mode_tag, rr), [])
                n = sum(b.n for b in group)
                if n == 0:
                    tot_rows.append([stack, mode_tag, f"1:{rr:g}", "0", "—", "—", "—", "—", "—", label])
                    continue
                trades = [t for b in group for t in b.trades]
                wr = 100.0 * sum(1 for t in trades if t.r > 0) / n
                sum_r = sum(t.r for t in trades)
                avg_r = sum_r / n
                wins = sum(t.r for t in trades if t.r > 0)
                losses = -sum(t.r for t in trades if t.r < 0)
                pf = (wins / losses) if losses > 0 else (float("inf") if wins > 0 else 0.0)
                # book-level maxDD is not the same as a combined equity; report sum of per-symbol DD as a loose bound
                # and a concatenated-by-entry-time DD as the book path.
                combo = _metrics(sorted(trades, key=lambda t: t.entry_time), "BOOK", "tot")
                tot_rows.append(
                    [
                        stack,
                        mode_tag,
                        f"1:{rr:g}",
                        str(n),
                        f"{wr:.1f}",
                        f"{sum_r:+.2f}",
                        _fmt_pf(pf),
                        f"{combo.max_dd_r:.2f}",
                        f"{avg_r:.3f}",
                        label,
                    ]
                )
    lines += [
        _md_table(
            ["stack", "entry", "RR", "n", "WR%", "sumR", "PF", "maxDD", "avgR", "mode"],
            tot_rows,
        ),
        "",
    ]

    # Prod-like
    lines += [
        "## Prod-like baseline — half@1:2 + BE + band-cross runner",
        "",
        "M15+H1 only. **Required compare rows are XAU and US100.** Other symbols are extra context.",
        "",
    ]
    prod_rows = []
    for sym in want:
        b = prod_books.get((sym, "M15_ST_V3"))
        if b is None:
            continue
        tag = "required" if sym in ("XAU", "US100") else "extra"
        prod_rows.append(
            [
                sym,
                "M15+H1",
                "prod-like",
                str(b.n),
                f"{b.wr_pct:.1f}",
                f"{b.sum_r:+.2f}",
                _fmt_pf(b.pf),
                f"{b.max_dd_r:.2f}",
                f"{b.avg_r:.3f}",
                tag,
            ]
        )
    if prod_rows:
        lines += [
            _md_table(
                ["symbol", "stack", "mode", "n", "WR%", "sumR", "PF", "maxDD", "avgR", "note"],
                prod_rows,
            ),
            "",
        ]
    else:
        lines += ["Prod-like cells were not run (M15 stack skipped or symbols missing).", ""]

    # Hypothesis checks
    def _group_sum(stack_id: str, mode_tag: str, rr: float) -> tuple[float, float, int]:
        group = totals.get((stack_id, mode_tag, rr), [])
        trades = [t for b in group for t in b.trades]
        n = len(trades)
        if n == 0:
            return 0.0, 0.0, 0
        wr = 100.0 * sum(1 for t in trades if t.r > 0) / n
        return wr, sum(t.r for t in trades), n

    hyp_lines = []
    for stack_id, stack, *_r in stacks:
        wr11, sr11, n11 = _group_sum(stack_id, "A", 1.0)
        wr15, sr15, n15 = _group_sum(stack_id, "A", 1.5)
        wr_b, sr_b, n_b = _group_sum(stack_id, "B", 1.0)
        wr_c, sr_c, n_c = _group_sum(stack_id, "C", 1.0)
        hyp_lines.append(
            f"- **{stack} A 1:1:** WR {wr11:.1f}% / {sr11:+.1f}R (n={n11}); "
            f"A 1:1.5: WR {wr15:.1f}% / {sr15:+.1f}R (n={n15})."
        )
        if n11 and n_b:
            beat = "beats" if (wr11 > wr_b or sr11 > sr_b) else "does not beat"
            hyp_lines.append(
                f"  A vs B at 1:1: band-cross+ST {beat} ST-flip "
                f"(B WR {wr_b:.1f}% / {sr_b:+.1f}R, n={n_b})."
            )
        if n11 and n_c:
            same = abs(n11 - n_c) == 0 and abs(sr11 - sr_c) < 1e-6
            hyp_lines.append(
                f"  A vs C at 1:1: "
                + (
                    "identical (wrong-side ST stops reject against-bias band-crosses)."
                    if same
                    else f"C n={n_c} WR {wr_c:.1f}% / {sr_c:+.1f}R — not identical to A."
                )
            )

    # Deploy call
    a11_m15 = totals.get(("M15_ST_V3", "A", 1.0), [])
    a15_m15 = totals.get(("M15_ST_V3", "A", 1.5), [])
    prod_req = [prod_books[k] for k in (("XAU", "M15_ST_V3"), ("US100", "M15_ST_V3")) if k in prod_books]

    def _sum_books(group: list[Book]) -> tuple[int, float, float]:
        trades = [t for b in group for t in b.trades]
        n = len(trades)
        if n == 0:
            return 0, 0.0, 0.0
        wr = 100.0 * sum(1 for t in trades if t.r > 0) / n
        return n, wr, sum(t.r for t in trades)

    n11, wr11, sr11 = _sum_books(a11_m15)
    n15, wr15, sr15 = _sum_books(a15_m15)
    npr, wrpr, srpr = _sum_books(prod_req)

    wr11_star = wr11 >= 45.0
    ev_cut = (n11 and npr and sr11 < srpr) or (n15 and npr and sr15 < srpr)

    if n11 == 0 and n15 == 0:
        call = (
            "**No call — the matrix did not produce M15+H1 A-cell trades.** "
            "Re-run after data loads."
        )
        deploy = "incomplete"
    elif wr11 >= 50.0 and sr11 > srpr and sr11 > 0:
        call = (
            f"**Worth papering 1:1 on M15+H1 A** this quarter "
            f"(book WR {wr11:.1f}% / {sr11:+.1f}R vs prod-like {wrpr:.1f}% / {srpr:+.1f}R on XAU+US100). "
            "Do **not** flip live Java until a second window agrees."
        )
        deploy = "paper_1to1"
    elif (wr11 >= 45.0 or wr15 >= 45.0) and (sr11 < srpr or sr15 < srpr or sr11 <= 0):
        call = (
            f"**Keep current ST_V3 management.** 1:1 "
            f"{'does' if wr11_star else 'does not'} lift WR into the 45–55% band "
            f"(M15+H1 A 1:1 WR {wr11:.1f}% / {sr11:+.1f}R, n={n11}; "
            f"1:1.5 WR {wr15:.1f}% / {sr15:+.1f}R, n={n15}) "
            f"but does not beat prod-like expectancy on the required XAU+US100 compare "
            f"(prod-like WR {wrpr:.1f}% / {srpr:+.1f}R, n={npr}). "
            "Fixed RR is a WR cosmetic, not a deploy."
        )
        deploy = "keep_prod"
    elif sr11 > 0 and (npr == 0 or sr11 >= srpr):
        call = (
            f"**Keep current ST_V3 management as the live default**, but 1:1 is not worse "
            f"on this quarter's M15+H1 A book ({sr11:+.1f}R vs prod-like {srpr:+.1f}R). "
            "Not enough to change Java; paper only if Adam wants a simpler ticket."
        )
        deploy = "keep_prod_watch"
    else:
        call = (
            f"**Keep current ST_V3 management.** Fixed 1:1 / 1:1.5 on M15+H1 A "
            f"({wr11:.1f}% / {sr11:+.1f}R and {wr15:.1f}% / {sr15:+.1f}R) "
            f"does not beat the prod-like half@1:2 + BE + band-cross runner "
            f"({wrpr:.1f}% / {srpr:+.1f}R on XAU+US100)."
        )
        deploy = "keep_prod"

    lines += [
        "## Hypothesis check",
        "",
        "Pre-registered: *1:1 may lift WR toward ~45–55% but cut expectancy; "
        "band-cross+ST bias likely beats ST-flip-only.*",
        "",
        *hyp_lines,
        "",
        f"- **WR lift toward 45–55% at 1:1 (M15+H1 A):** "
        f"{'yes' if wr11_star else 'no'} (WR {wr11:.1f}%).",
        f"- **Expectancy cut vs prod-like (XAU+US100 M15+H1):** "
        f"{'yes' if ev_cut else 'no or incomplete'} "
        f"(A 1:1 {sr11:+.1f}R vs prod-like {srpr:+.1f}R).",
        "",
        "## Call",
        "",
        call,
        "",
        "- Do **not** change prod Java defaults (`HtsTradeService.ST_V3_TP1_MULT`, "
        "band-cross runner, satellite universes).",
        "- Do **not** merge this note as a live switch.",
        "",
        "## How to rerun",
        "",
        "```bash",
        "python3 -m pip install -r tools/ha_hunt_st_compare/requirements.txt",
        "python3 -m tools.ha_hunt_st_compare.test_simulator",
        "python3 -m tools.ha_hunt_st_compare.run_fixed_rr_q",
        "```",
        "",
        "Flags: `--symbols XAU,US100,BTC` · `--no-extra` · `--no-h1` · `--no-c`.",
        "Caches live under `tools/ha_hunt_st_compare/cache/` (gitignored).",
        "",
    ]

    OUT_MD.write_text("\n".join(lines) + "\n")
    payload = {
        "generated": generated,
        "window": {
            "trade_start": TRADE_START.isoformat(),
            "trade_end": TRADE_END.isoformat(),
            "fetch_start": FETCH_START.isoformat(),
            "warmup_days": WARMUP_DAYS,
            "calendar_days": (TRADE_END - TRADE_START).days,
        },
        "locked": {
            "st_atr_len": 7,
            "st_factor": 2.0,
            "pyramid": False,
            "stop": "supertrend_line",
            "fixed_rr": [1.0, 1.5],
            "prod_like": "half_1to2_be_band_cross",
        },
        "coverage": cov,
        "series_notes": SERIES_NOTES,
        "load_errors": load_errors,
        "call": {"deploy": deploy, "text": call},
        "results": results,
    }
    OUT_JSON.write_text(json.dumps(payload, indent=2) + "\n")
    print(f"wrote {OUT_MD}", flush=True)
    print(f"wrote {OUT_JSON}", flush=True)
    print(call, flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
