#!/usr/bin/env python3
"""Run the locked HA-Hunt M45/M5 + M45 ST 12-month bake-off and write report artifacts."""

from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from tools.ha_hunt_st_compare.ohlc import SYMBOLS, coverage_table, load_symbol, resample_bars
from tools.ha_hunt_st_compare.simulator import (
    Book,
    Params,
    h1_compare_variants,
    ha_exit_variants,
    ha_exit_wr_perms,
    m15_ha_exit_variants,
    variants,
    simulate,
)

DOCS = ROOT / "docs"
OUT_MD = DOCS / "ha-hunt-m45-m5-st-12mo.md"
OUT_JSON = DOCS / "ha-hunt-m45-m5-st-12mo.json"

START = datetime(2025, 9, 11, tzinfo=timezone.utc)
END = datetime(2026, 9, 11, tzinfo=timezone.utc)


def _book_row(b: Book) -> dict:
    return {
        "symbol": b.symbol,
        "variant": b.variant,
        "n": b.n,
        "wr_pct": round(b.wr_pct, 2),
        "wr_full_pct": round(b.wr_full_pct, 2),
        "sum_r": round(b.sum_r, 3),
        "avg_r": round(b.avg_r, 4),
        "max_dd_r": round(b.max_dd_r, 3),
        "pf": None if b.pf == float("inf") else round(b.pf, 3),
        "exits": b.exits,
        "n_long": b.n_long,
        "n_short": b.n_short,
        "sum_r_long": round(b.sum_r_long, 3),
        "sum_r_short": round(b.sum_r_short, 3),
        "n_tp1": b.n_tp1,
    }


def _fmt_pf(pf: float) -> str:
    if pf == float("inf"):
        return "∞"
    return f"{pf:.2f}"


def _md_table(headers: list[str], rows: list[list[str]]) -> str:
    lines = ["| " + " | ".join(headers) + " |", "| " + " | ".join("---" for _ in headers) + " |"]
    for r in rows:
        lines.append("| " + " | ".join(r) + " |")
    return "\n".join(lines)


def _rank_key(b: Book) -> tuple:
    pf = b.pf if b.pf != float("inf") else 99.0
    return (b.sum_r, pf, -b.max_dd_r, b.n)


def main() -> int:
    DOCS.mkdir(parents=True, exist_ok=True)
    print(f"window {START.isoformat()} → {END.isoformat()}", flush=True)

    bars = {}
    load_errors = {}
    for sym in SYMBOLS:
        print(f"load {sym}…", flush=True)
        try:
            bars[sym] = load_symbol(sym, START, END, prefer_capital=True)
            m = bars[sym].meta
            print(f"  {m.source}  n_m5={m.n_m5}  {m.first} → {m.last}", flush=True)
        except Exception as e:
            load_errors[sym] = str(e)
            print(f"  FAIL {e}", flush=True)

    if not bars:
        print("no symbols loaded", file=sys.stderr)
        return 1

    cells: list[Book] = []
    for p in variants():
        print(f"variant {p.name}", flush=True)
        for sym, b in bars.items():
            book = simulate(sym, b, p)
            cells.append(book)
            print(
                f"  {sym:7} n={book.n:4}  WR={book.wr_pct:5.1f}%  sumR={book.sum_r:8.2f}  "
                f"avgR={book.avg_r:6.3f}  DD={book.max_dd_r:6.2f}  PF={_fmt_pf(book.pf)}  {book.exits}",
                flush=True,
            )

    # Totals per variant (independent 1-position books summed)
    by_var: dict[str, list[Book]] = {}
    for b in cells:
        by_var.setdefault(b.variant, []).append(b)

    totals: list[Book] = []
    for name, books in by_var.items():
        t = Book(symbol="BOOK", variant=name)
        t.trades = [tr for b in books for tr in b.trades]
        t.n = sum(b.n for b in books)
        t.n_long = sum(b.n_long for b in books)
        t.n_short = sum(b.n_short for b in books)
        t.n_tp1 = sum(b.n_tp1 for b in books)
        t.sum_r = sum(b.sum_r for b in books)
        t.sum_r_long = sum(b.sum_r_long for b in books)
        t.sum_r_short = sum(b.sum_r_short for b in books)
        t.avg_r = t.sum_r / t.n if t.n else 0.0
        wins = sum(tr.r for tr in t.trades if tr.r > 0)
        losses = -sum(tr.r for tr in t.trades if tr.r < 0)
        t.wr_pct = 100.0 * sum(1 for tr in t.trades if tr.r > 0) / t.n if t.n else 0.0
        t.wr_full_pct = 100.0 * sum(1 for tr in t.trades if getattr(tr, "r_full", tr.r) > 0) / t.n if t.n else 0.0
        t.pf = wins / losses if losses > 0 else (float("inf") if wins > 0 else 0.0)
        # Combined DD on concatenated per-symbol streams is not a real portfolio.
        # Report the worst single-name DD and a chronological-all-symbols DD.
        t.max_dd_r = max((b.max_dd_r for b in books), default=0.0)
        t.exits = {}
        for b in books:
            for k, v in b.exits.items():
                t.exits[k] = t.exits.get(k, 0) + v
        totals.append(t)

    totals_sorted = sorted(totals, key=_rank_key, reverse=True)
    baseline = next(t for t in totals if t.variant == "baseline_144")
    base_by_sym = sorted(
        [b for b in cells if b.variant == "baseline_144"],
        key=_rank_key,
        reverse=True,
    )

    per_ticker_winners = {}
    for sym in bars:
        best = max([b for b in cells if b.symbol == sym], key=_rank_key)
        per_ticker_winners[sym] = best.variant

    cov = coverage_table(bars)
    payload = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "window": {"start": START.isoformat(), "end": END.isoformat()},
        "locked_baseline": {
            "chart": "M5",
            "structure": "M45 large RMA high-low band",
            "trigger": "M5 small-band CROSS (first close beyond M5 fast band)",
            "bias_sl": "closed M45 Supertrend (same line)",
            "slow_len": 144,
            "fast_len": 33,
            "st": {"atr": 10, "factor": 2.0},
            "tp1": "2 × |entry−SL|; runner trails M45 ST; exit ST flip and/or M45 slow-band body",
            "m45_gate": True,
            "one_position": True,
            "same_bar": "conservative: stop wins vs TP1",
            "r_accounting": "full-position exit R (Pine overlay does not scale out); r_split is HTS 50/50",
            "htf_closed": "last completed M45 then Pine [1] shift",
        },
        "coverage": cov,
        "load_errors": load_errors,
        "baseline_by_symbol": [_book_row(b) for b in base_by_sym],
        "baseline_book": _book_row(baseline),
        "variant_totals": [_book_row(t) for t in totals_sorted],
        "all_cells": [_book_row(b) for b in cells],
        "per_ticker_winner": per_ticker_winners,
        "overall_winner": totals_sorted[0].variant if totals_sorted else None,
        "recommendation": None,
    }

    winner = totals_sorted[0] if totals_sorted else None
    best_ticker = base_by_sym[0] if base_by_sym else None
    rec_lines = []
    if best_ticker:
        rec_lines.append(
            f"Best baseline ticker: **{best_ticker.symbol}** "
            f"(n={best_ticker.n}, WR={best_ticker.wr_pct:.1f}%, sumR={best_ticker.sum_r:.2f}, "
            f"PF={_fmt_pf(best_ticker.pf)}, maxDD={best_ticker.max_dd_r:.2f}R)."
        )
    if winner:
        rec_lines.append(
            f"Best permutation overall (sumR): **{winner.variant}** "
            f"n={winner.n} WR={winner.wr_pct:.1f}% sumR={winner.sum_r:.2f} PF={_fmt_pf(winner.pf)}."
        )
    # Prefer a slow=144 recommendation even if slow100 wins the table.
    slow144 = [t for t in totals_sorted if t.variant != "slow100"]
    if slow144:
        rec_lines.append(
            f"Best slow=144 permutation: **{slow144[0].variant}** "
            f"sumR={slow144[0].sum_r:.2f} PF={_fmt_pf(slow144[0].pf)} "
            f"(slow100 is A/B only; Adam locked 144)."
        )
    payload["recommendation"] = " ".join(rec_lines)

    # Markdown
    def book_cells(b: Book) -> list[str]:
        return [
            b.symbol if b.symbol != "BOOK" else b.variant,
            str(b.n),
            f"{b.wr_pct:.1f}",
            f"{b.sum_r:.2f}",
            f"{b.avg_r:.3f}",
            f"{b.max_dd_r:.2f}",
            _fmt_pf(b.pf),
            str(b.exits.get("stop", 0)),
            str(b.exits.get("m45_st_flip", 0)),
            str(b.exits.get("m45_slow_band", 0)),
            str(b.exits.get("open_eod", 0)),
            f"{b.n_long}/{b.n_short}",
            str(b.n_tp1),
        ]

    hdr = ["name", "n", "WR%", "sumR", "avgR", "maxDD(R)", "PF", "stop", "st_flip", "slow", "eod", "L/S", "TP1"]

    cov_rows = [
        [c["symbol"], c["source"], c["first"][:19], c["last"][:19], str(c["n_m5"]), f"{c['coverage_days']:.1f}", c["note"][:80]]
        for c in cov
    ]

    md = []
    md.append("# HA-Hunt M45 / M5 + M45 Supertrend — ~12-month research backtest")
    md.append("")
    md.append(f"Generated `{payload['generated_at']}`. Simulator: `tools/ha_hunt_st_compare` matching `pine/ha_hunt_m45_m5_st.pine`.")
    md.append("")
    md.append("**No Java / prod HTS changes.** One position at a time. Conservative same-bar (stop before TP1).")
    md.append("")
    md.append("## Locked baseline")
    md.append("")
    md.append("- Chart / entry TF: **M5**")
    md.append("- Structure: **M45** large RMA high-low band; gate **ON**")
    md.append("- Trigger: M5 small-band **CROSS** (first close beyond M5 fast band); `bandCrossStrict` **off**")
    md.append("- Bias + SL: closed **M45 Supertrend** (same line). Long only ST bull, short only ST bear.")
    md.append("- Slow RMA **144** (Adam), fast **33**. ST ATR **10**, factor **2.0**. `capReg` **2**.")
    md.append("- TP1 = 2 × |entry − SL|; runner trails M45 ST; full exit on M45 ST flip and/or M45 slow-band body.")
    md.append("- Closed HTF = last completed M45 bar, then Pine `f_*Closed()[1]` (no forming-bar leak).")
    md.append("- R = full-position exit / initial 1R (Pine does not scale out). `r_split` in JSON is HTS 50/50.")
    md.append("")
    md.append("## Data coverage")
    md.append("")
    md.append(
        "Capital DEMO mid caches / API keys were **not** available in this agent environment. "
        "Prices are **real** HistData M1 OHLC resampled to M5 (XAU, US100=NSXUSD, GER40=GRXEUR, EURUSD) "
        "and Coinbase Exchange 5m for BTCUSD. That is **not** Capital mid; do not treat levels as "
        "fillable Capital quotes. Gaps are stated per symbol — no invented bars."
    )
    md.append("")
    md.append(_md_table(["symbol", "source", "first", "last", "n_m5", "days", "note"], cov_rows))
    if load_errors:
        md.append("")
        md.append("Load errors: " + json.dumps(load_errors))
    md.append("")
    md.append("## Baseline (slow=144) per symbol + book")
    md.append("")
    md.append(_md_table(hdr, [book_cells(b) for b in base_by_sym] + [book_cells(baseline)]))
    md.append("")
    md.append("Book row is the sum of independent one-position-per-name books (not a single portfolio slot).")
    md.append("maxDD on the BOOK row is the **worst single-name** baseline DD, not a combined equity DD.")
    md.append("")
    md.append("## Permutation bake-off (book totals)")
    md.append("")
    md.append(_md_table(hdr, [book_cells(t) for t in totals_sorted]))
    md.append("")
    md.append("Variants (one-at-a-time from locked baseline, plus two cheap combos):")
    md.append("")
    md.append("| variant | change |")
    md.append("| --- | --- |")
    md.append("| baseline_144 | locked stack (slow 144, loose cross, gate on, cap 2, ST stop, factor 2, both sides) |")
    md.append("| cap1 | capReg = 1 |")
    md.append("| strict | bandCrossStrict on |")
    md.append("| nogate | M45 structure gate off |")
    md.append("| slow100 | slow RMA 100 (A/B only) |")
    md.append("| stop_atr | SL = M45 ATR14 × 2.5 |")
    md.append("| stop_band | SL = M45 fast-band far edge ± 0.25×width |")
    md.append("| st3 | Supertrend factor 3.0 |")
    md.append("| long_only | longs only |")
    md.append("| cap1_strict | capReg 1 + strict band-clear |")
    md.append("")
    md.append("## Per-ticker winner (by sumR)")
    md.append("")
    win_rows = []
    for sym in bars:
        ranked = sorted([b for b in cells if b.symbol == sym], key=_rank_key, reverse=True)
        top = ranked[0]
        win_rows.append([sym, top.variant, str(top.n), f"{top.sum_r:.2f}", _fmt_pf(top.pf), f"{top.wr_pct:.1f}"])
    md.append(_md_table(["symbol", "best variant", "n", "sumR", "PF", "WR%"], win_rows))
    md.append("")
    md.append("## Recommendation")
    md.append("")
    md.append(payload["recommendation"])
    md.append("")
    md.append("Keep **slow=144**. Use `slow100` only as the A/B that Adam asked for — it is not the locked stack.")
    md.append("")
    md.append("## How to rerun")
    md.append("")
    md.append("```bash")
    md.append("python3 -m tools.ha_hunt_st_compare.run_bakeoff")
    md.append("```")
    md.append("")
    md.append("With Capital DEMO env (`CAPITAL_API_KEY`, `CAPITAL_EMAIL`, `CAPITAL_API_PASSWORD`) the loader prefers Capital mid M5.")
    md.append("Caches live under `tools/ha_hunt_st_compare/cache/` (gitignored).")
    md.append("")

    OUT_MD.write_text("\n".join(md) + "\n")
    OUT_JSON.write_text(json.dumps(payload, indent=2) + "\n")
    print(f"wrote {OUT_MD}")
    print(f"wrote {OUT_JSON}")
    print("OVERALL", payload["overall_winner"], payload["recommendation"])
    return 0


def _load_bars():
    bars = {}
    load_errors = {}
    for sym in SYMBOLS:
        print(f"load {sym}…", flush=True)
        try:
            bars[sym] = load_symbol(sym, START, END, prefer_capital=True)
            m = bars[sym].meta
            print(f"  {m.source}  n_m5={m.n_m5}  {m.first} → {m.last}", flush=True)
        except Exception as e:
            load_errors[sym] = str(e)
            print(f"  FAIL {e}", flush=True)
    return bars, load_errors


def _run_params(params: list[Params], bars: dict) -> list[Book]:
    cells: list[Book] = []
    for p in params:
        print(f"variant {p.name}", flush=True)
        for sym, b in bars.items():
            book = simulate(sym, b, p)
            cells.append(book)
            print(
                f"  {sym:7} n={book.n:4}  WR={book.wr_pct:5.1f}%  sumR={book.sum_r:8.2f}  "
                f"avgR={book.avg_r:6.3f}  DD={book.max_dd_r:6.2f}  PF={_fmt_pf(book.pf)}  {book.exits}",
                flush=True,
            )
    return cells


def _totals(cells: list[Book]) -> list[Book]:
    by_var: dict[str, list[Book]] = {}
    for b in cells:
        by_var.setdefault(b.variant, []).append(b)
    totals: list[Book] = []
    for name, books in by_var.items():
        t = Book(symbol="BOOK", variant=name)
        t.trades = [tr for b in books for tr in b.trades]
        t.n = sum(b.n for b in books)
        t.n_long = sum(b.n_long for b in books)
        t.n_short = sum(b.n_short for b in books)
        t.n_tp1 = sum(b.n_tp1 for b in books)
        t.sum_r = sum(b.sum_r for b in books)
        t.sum_r_long = sum(b.sum_r_long for b in books)
        t.sum_r_short = sum(b.sum_r_short for b in books)
        t.avg_r = t.sum_r / t.n if t.n else 0.0
        wins = sum(tr.r for tr in t.trades if tr.r > 0)
        losses = -sum(tr.r for tr in t.trades if tr.r < 0)
        t.wr_pct = 100.0 * sum(1 for tr in t.trades if tr.r > 0) / t.n if t.n else 0.0
        t.wr_full_pct = 100.0 * sum(1 for tr in t.trades if getattr(tr, "r_full", tr.r) > 0) / t.n if t.n else 0.0
        t.pf = wins / losses if losses > 0 else (float("inf") if wins > 0 else 0.0)
        t.max_dd_r = max((b.max_dd_r for b in books), default=0.0)
        t.exits = {}
        for b in books:
            for k, v in b.exits.items():
                t.exits[k] = t.exits.get(k, 0) + v
        totals.append(t)
    return totals


def _h1_book_cells(b: Book) -> list[str]:
    return [
        b.symbol if b.symbol != "BOOK" else b.variant,
        str(b.n),
        f"{b.wr_pct:.1f}",
        f"{b.sum_r:.2f}",
        f"{b.avg_r:.3f}",
        f"{b.max_dd_r:.2f}",
        _fmt_pf(b.pf),
        str(b.exits.get("stop", 0)),
        str(b.exits.get("trail", 0)),
        str(b.exits.get("h1_st_flip", 0) + b.exits.get("m45_st_flip", 0)),
        str(b.exits.get("m45_slow_band", 0)),
        str(b.exits.get("open_eod", 0)),
        f"{b.n_long}/{b.n_short}",
        str(b.n_tp1),
    ]


LABELS = {
    "baseline_144": "A M45 ST + full trail",
    "m45st_partial": "B M45 ST + 50% TP1 + BE",
    "h1st_partial": "C H1 ST + 50% TP1 + BE",
    "h1st_full": "D H1 ST + full trail",
}
MATRIX_ORDER = ["baseline_144", "m45st_partial", "h1st_partial", "h1st_full"]


def run_h1_followup() -> int:
    """2×2 matrix: M45/H1 ST × full-trail / 50% TP1 on the locked 12m book."""
    print(f"ST×TP1 matrix  {START.isoformat()} → {END.isoformat()}", flush=True)
    bars, load_errors = _load_bars()
    if not bars:
        return 1
    cells = _run_params(h1_compare_variants(), bars)
    totals = _totals(cells)
    by_name = {t.variant: t for t in totals}

    payload = {}
    if OUT_JSON.exists():
        payload = json.loads(OUT_JSON.read_text())

    def cell_of(name: str, sym: str) -> Book:
        return next(b for b in cells if b.variant == name and b.symbol == sym)

    matrix_by_symbol = {}
    for sym in bars:
        matrix_by_symbol[sym] = {name: _book_row(cell_of(name, sym)) for name in MATRIX_ORDER}

    winners = {}
    for sym in list(bars) + ["BOOK"]:
        pool = [by_name[n] for n in MATRIX_ORDER] if sym == "BOOK" else [cell_of(n, sym) for n in MATRIX_ORDER]
        best = max(pool, key=_rank_key)
        winners[sym] = LABELS[best.variant]

    rec_bits = [
        f"Overall book winner: **{winners['BOOK']}**.",
        f"XAU winner: **{winners['XAU']}**.",
        f"US100 winner: **{winners['US100']}**.",
    ]
    a, b, c, d = (by_name[n] for n in MATRIX_ORDER)
    rec_bits.append(
        f"Book sumR  A={a.sum_r:.1f}  B={b.sum_r:.1f}  C={c.sum_r:.1f}  D={d.sum_r:.1f}. "
        f"Half-TP1 on M45 (B−A) = {b.sum_r - a.sum_r:+.1f}R; "
        f"H1 vs M45 full (D−A) = {d.sum_r - a.sum_r:+.1f}R; "
        f"H1 half vs M45 full (C−A) = {c.sum_r - a.sum_r:+.1f}R."
    )
    rec = " ".join(rec_bits)

    payload["tp1_matrix"] = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "cells": {
            "A": "M45 ST + full trail after TP1 touch (locked baseline; no half close)",
            "B": "M45 ST + half TP1 (50% at +2R, runner stop → BE, then M45 ST trail only in favor)",
            "C": "H1 ST + half TP1 (50% at +2R, runner stop → BE, then H1 ST trail only in favor)",
            "D": "H1 ST + full trail after TP1 (isolation)",
        },
        "rules": {
            "keep": "M5 band-cross, M45 RMA structure slow=144, gate ON, cap 2, both sides",
            "half_r": "TP1 books +1R from the closed half; combined R = 1.0 + 0.5×runner_R after TP1; −1R if stopped before TP1",
            "same_bar": "stop wins vs TP1 (no scale-out on that bar)",
        },
        "coverage": coverage_table(bars),
        "load_errors": load_errors,
        "by_symbol": matrix_by_symbol,
        "book": {name: _book_row(by_name[name]) for name in MATRIX_ORDER},
        "winner": winners,
        "recommendation": rec,
    }
    # Keep prior key so older readers still see H1 numbers.
    payload["h1_followup"] = payload["tp1_matrix"]

    def metrics_row(b: Book, label: str) -> list[str]:
        return [
            label,
            str(b.n),
            f"{b.wr_pct:.1f}",
            f"{b.sum_r:.2f}",
            f"{b.avg_r:.3f}",
            f"{b.max_dd_r:.2f}",
            _fmt_pf(b.pf),
        ]

    hdr = ["cell", "n", "WR%", "sumR", "avgR", "maxDD(R)", "PF"]
    book_rows = [metrics_row(by_name[n], LABELS[n]) for n in MATRIX_ORDER]

    # Wide per-symbol: one row per name with A/B/C/D sumR + winner
    wide = []
    for sym in list(bars) + ["BOOK"]:
        pool = [by_name[n] for n in MATRIX_ORDER] if sym == "BOOK" else [cell_of(n, sym) for n in MATRIX_ORDER]
        wide.append(
            [sym]
            + [f"{x.sum_r:.2f}" for x in pool]
            + [f"{x.wr_pct:.1f}" for x in pool]
            + [f"{x.max_dd_r:.1f}" for x in pool]
            + [winners[sym]]
        )

    detail = []
    for sym in bars:
        detail.append(f"#### {sym}")
        detail.append("")
        detail.append(_md_table(hdr, [metrics_row(cell_of(n, sym), LABELS[n]) for n in MATRIX_ORDER]))
        detail.append("")

    section = []
    section.append("## ST TF × TP1 matrix (A/B/C/D)")
    section.append("")
    section.append(
        f"Generated `{payload['tp1_matrix']['generated_at']}`. Same 12m data, slow **144**, M5 band-cross, "
        "M45 structure gate **ON**, cap 2, both sides. Locked baseline **A did not half-close** at TP1."
    )
    section.append("")
    section.append("| | full trail after TP1 touch (no forced BE) | 50% off at 1:2, runner → BE, then trail in favor |")
    section.append("| --- | --- | --- |")
    section.append("| **M45 ST** bias+SL | **A** `baseline_144` (locked) | **B** `m45st_partial` |")
    section.append("| **H1 ST** bias+SL | **D** `h1st_full` | **C** `h1st_partial` |")
    section.append("")
    section.append("Half-TP1 R: stop before TP1 = −1R; after TP1 = **+1.0 + 0.5 × runner_R** (BE runner = +1.0R). Same-bar stop beats TP1 (no scale / no BE that bar).")
    section.append("B/C: after the 50% fill, remaining stop jumps to **entry**, then M45/H1 ST may only tighten. A/D unchanged — no forced BE.")
    section.append("")
    section.append("### One table — book")
    section.append("")
    section.append(_md_table(hdr, book_rows))
    section.append("")
    section.append("### One table — per symbol sumR / WR% / maxDD")
    section.append("")
    section.append(
        _md_table(
            [
                "symbol",
                "A sumR",
                "B sumR",
                "C sumR",
                "D sumR",
                "A WR",
                "B WR",
                "C WR",
                "D WR",
                "A DD",
                "B DD",
                "C DD",
                "D DD",
                "winner",
            ],
            wide,
        )
    )
    section.append("")
    section.append("### Detail (n / WR / sumR / avgR / DD / PF)")
    section.append("")
    section.extend(detail)
    section.append("### Call")
    section.append("")
    section.append(rec)
    section.append("")
    xau_a = cell_of("baseline_144", "XAU").sum_r
    xau_b = cell_of("m45st_partial", "XAU").sum_r
    nq_a = cell_of("baseline_144", "US100").sum_r
    nq_b = cell_of("m45st_partial", "US100").sum_r
    section.append(
        f"XAU: A {xau_a:+.1f} vs B {xau_b:+.1f} (half on M45 {xau_b - xau_a:+.1f}R). "
        f"US100: A {nq_a:+.1f} vs B {nq_b:+.1f} (half on M45 {nq_b - nq_a:+.1f}R). "
        f"Overall book: A {a.sum_r:+.1f} vs B {b.sum_r:+.1f} vs C {c.sum_r:+.1f} vs D {d.sum_r:+.1f}."
    )
    section.append("")

    text = OUT_MD.read_text() if OUT_MD.exists() else ""
    marks = ("## ST TF × TP1 matrix (A/B/C/D)", "## H1 Supertrend + 50% TP1 (follow-up)")
    how = "## How to rerun"
    cut = None
    for m in marks:
        if m in text:
            cut = m
            break
    howto = (
        "## How to rerun\n\n"
        "```bash\n"
        "python3 -m tools.ha_hunt_st_compare.run_bakeoff\n"
        "python3 -m tools.ha_hunt_st_compare.run_bakeoff --h1-followup\n"
        "```\n\n"
        "With Capital DEMO env (`CAPITAL_API_KEY`, `CAPITAL_EMAIL`, `CAPITAL_API_PASSWORD`) the loader prefers Capital mid M5.\n"
        "Caches live under `tools/ha_hunt_st_compare/cache/` (gitignored).\n"
    )
    if cut:
        pre = text.split(cut)[0].rstrip()
        text = pre + "\n\n" + "\n".join(section) + "\n" + howto
    elif how in text:
        pre = text.split(how)[0].rstrip()
        text = pre + "\n\n" + "\n".join(section) + "\n" + howto
    else:
        text = text.rstrip() + "\n\n" + "\n".join(section) + "\n" + howto

    OUT_MD.write_text(text if text.endswith("\n") else text + "\n")
    OUT_JSON.write_text(json.dumps(payload, indent=2) + "\n")
    print(f"wrote {OUT_MD}")
    print(f"wrote {OUT_JSON}")
    print("MATRIX REC", rec)
    return 0


HA_LABELS = {
    "ha_m45": "A M45 ST + half TP1 + BE + M5 HA flip",
    "ha_h1": "B H1 ST + half TP1 + BE + M5 HA flip",
    "baseline_144": "C OLD M45 ST full-trail (no half, ST trail / ST flip / slow-band)",
}
HA_ORDER = ["ha_m45", "ha_h1"]
HA_SECTION = "## M5 HA-flip runner (A vs B)"


def _old_baseline_from_payload(payload: dict) -> tuple[Book | None, dict[str, Book]]:
    """Reference C = prior locked full-trail book (do not re-simulate)."""
    raw_book = payload.get("baseline_book") or (payload.get("tp1_matrix") or {}).get("book", {}).get("baseline_144")
    raw_syms = payload.get("baseline_by_symbol") or []
    if not raw_book:
        return None, {}

    def as_book(row: dict, symbol: str) -> Book:
        b = Book(symbol=symbol, variant="baseline_144")
        b.n = int(row.get("n") or 0)
        b.wr_pct = float(row.get("wr_pct") or 0)
        b.sum_r = float(row.get("sum_r") or 0)
        b.avg_r = float(row.get("avg_r") or 0)
        b.max_dd_r = float(row.get("max_dd_r") or 0)
        b.pf = float(row.get("pf") or 0)
        b.n_long = int(row.get("n_long") or 0)
        b.n_short = int(row.get("n_short") or 0)
        b.n_tp1 = int(row.get("n_tp1") or 0)
        b.exits = dict(row.get("exits") or {})
        # OLD full-trail: scaled WR == full-size WR
        b.wr_full_pct = float(row.get("wr_full_pct") or b.wr_pct)
        return b

    book = as_book(raw_book, "BOOK")
    by_sym = {row["symbol"]: as_book(row, row["symbol"]) for row in raw_syms}
    return book, by_sym


def run_ha_exit() -> int:
    """Half TP1 + BE + M5 HA flip. A = M45 ST, B = H1 ST. C = OLD full-trail ref."""
    print(f"HA-flip runner  {START.isoformat()} → {END.isoformat()}", flush=True)
    bars, load_errors = _load_bars()
    if not bars:
        return 1

    cells = _run_params(ha_exit_variants(), bars)
    totals = _totals(cells)
    by_name = {t.variant: t for t in totals}

    payload = json.loads(OUT_JSON.read_text()) if OUT_JSON.exists() else {}
    old_book, old_by_sym = _old_baseline_from_payload(payload)

    def cell_of(name: str, sym: str) -> Book:
        return next(b for b in cells if b.variant == name and b.symbol == sym)

    by_symbol = {}
    for sym in bars:
        by_symbol[sym] = {name: _book_row(cell_of(name, sym)) for name in HA_ORDER}

    winners = {}
    for sym in list(bars) + ["BOOK"]:
        pool = [by_name[n] for n in HA_ORDER] if sym == "BOOK" else [cell_of(n, sym) for n in HA_ORDER]
        best = max(pool, key=_rank_key)
        winners[sym] = HA_LABELS[best.variant]

    a, b = by_name["ha_m45"], by_name["ha_h1"]
    rec = (
        f"HA-exit book: A (M45) n={a.n} WR={a.wr_pct:.1f}% sumR={a.sum_r:+.1f} PF={_fmt_pf(a.pf)}; "
        f"B (H1) n={b.n} WR={b.wr_pct:.1f}% sumR={b.sum_r:+.1f} PF={_fmt_pf(b.pf)}. "
        f"ST TF winner on this exit: **{winners['BOOK']}**."
    )

    perm_cells: list[Book] = []
    perm_totals: list[Book] = []
    wr_target = 50.0
    need_perms = a.wr_pct < wr_target - 1.0 and b.wr_pct < wr_target - 1.0
    if need_perms:
        print("HA-exit WR below ~50% — running stricter WR permutations (slow 144).", flush=True)
        better_tf = 45 if a.wr_pct >= b.wr_pct else 60
        other_tf = 60 if better_tf == 45 else 45
        perm_params = ha_exit_wr_perms(better_tf) + ha_exit_wr_perms(other_tf)
        perm_cells = _run_params(perm_params, bars)
        perm_totals = _totals(perm_cells)

    payload["ha_exit"] = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "cells": {
            "A": "M45 ST bias+SL + 50% TP1 + runner BE + M5 HA colour flip (no ST trail / no slow-band / no ST flip)",
            "B": "H1 ST bias+SL + 50% TP1 + runner BE + M5 HA colour flip (same runner)",
            "C": "OLD M45 ST full-trail reference (no half, ST trail + ST flip + slow-band) — not this exit",
        },
        "rules": {
            "tp1": "50% at 1:2, remaining stop → entry immediately",
            "runner": "confirmed M5 HA colour flip against the position (body close), or BE hit",
            "st": "bias + initial SL only; no trail after TP1",
            "st_flip_secondary": "OFF",
            "win": "trade is a win when total R > 0 (typical +1R if half fills and runner BE)",
        },
        "coverage": coverage_table(bars),
        "load_errors": load_errors,
        "by_symbol": by_symbol,
        "book": {name: _book_row(by_name[name]) for name in HA_ORDER},
        "old_full_trail_book": _book_row(old_book) if old_book else None,
        "winner": winners,
        "recommendation": rec,
        "wr_perms": {
            "ran": need_perms,
            "book": [_book_row(t) for t in sorted(perm_totals, key=_rank_key, reverse=True)],
            "cells": [_book_row(c) for c in perm_cells],
        },
    }

    def metrics_row(b: Book, label: str) -> list[str]:
        tp1_pct = (100.0 * b.n_tp1 / b.n) if b.n else 0.0
        return [
            label,
            str(b.n),
            f"{b.wr_pct:.1f}",
            f"{b.wr_full_pct:.1f}",
            f"{tp1_pct:.1f}",
            f"{b.sum_r:.2f}",
            f"{b.avg_r:.3f}",
            f"{b.max_dd_r:.2f}",
            _fmt_pf(b.pf),
        ]

    hdr = ["cell", "n", "WR% (R>0)", "WR% full-size", "TP1%", "sumR", "avgR", "maxDD(R)", "PF"]
    book_rows = [metrics_row(by_name[n], HA_LABELS[n]) for n in HA_ORDER]
    if old_book:
        book_rows.append(metrics_row(old_book, HA_LABELS["baseline_144"]))

    wide = []
    for sym in list(bars) + ["BOOK"]:
        pool = [by_name[n] for n in HA_ORDER] if sym == "BOOK" else [cell_of(n, sym) for n in HA_ORDER]
        old = old_book if sym == "BOOK" else old_by_sym.get(sym)
        wide.append(
            [sym]
            + [f"{x.sum_r:.2f}" for x in pool]
            + ([f"{old.sum_r:.2f}"] if old else ["—"])
            + [f"{x.wr_pct:.1f}" for x in pool]
            + ([f"{old.wr_pct:.1f}"] if old else ["—"])
            + [winners[sym]]
        )

    detail = []
    for sym in bars:
        detail.append(f"#### {sym}")
        detail.append("")
        rows = [metrics_row(cell_of(n, sym), HA_LABELS[n]) for n in HA_ORDER]
        if sym in old_by_sym:
            rows.append(metrics_row(old_by_sym[sym], HA_LABELS["baseline_144"]))
        detail.append(_md_table(hdr, rows))
        detail.append("")
        a_s, b_s = cell_of("ha_m45", sym), cell_of("ha_h1", sym)
        detail.append(
            f"Exits A: `{a_s.exits}` · B: `{b_s.exits}`. "
            f"TP1 fills A {a_s.n_tp1}/{a_s.n} · B {b_s.n_tp1}/{b_s.n}."
        )
        detail.append("")

    perm_md = []
    if perm_totals:
        perm_md.append("### WR permutations (slow 144, same HA-exit lock)")
        perm_md.append("")
        perm_md.append(
            "Adam target **~50% WR** (win = scaled total R > 0). OLD full-trail book was **32.9%**. "
            "One-at-a-time plus a few stricter combos. `longonly` is all names; "
            "XAU+US100 long-only is those two books only."
        )
        perm_md.append("")
        perm_rows = [metrics_row(t, t.variant) for t in sorted(perm_totals, key=_rank_key, reverse=True)]
        perm_md.append(_md_table(hdr, perm_rows))
        perm_md.append("")
        focus = []
        for t in perm_totals:
            xau = next((c for c in perm_cells if c.variant == t.variant and c.symbol == "XAU"), None)
            nq = next((c for c in perm_cells if c.variant == t.variant and c.symbol == "US100"), None)
            if xau and nq:
                focus.append(
                    [t.variant, f"{t.wr_pct:.1f}", f"{t.sum_r:.1f}", f"{xau.sum_r:.1f}", f"{nq.sum_r:.1f}", f"{xau.wr_pct:.1f}", f"{nq.wr_pct:.1f}"]
                )
        if focus:
            perm_md.append(
                _md_table(
                    ["perm", "book WR%", "book sumR", "XAU sumR", "US100 sumR", "XAU WR", "US100 WR"],
                    focus,
                )
            )
            perm_md.append("")
        hit = [t for t in perm_totals if t.wr_pct >= wr_target - 0.5]
        if hit:
            best_hit = max(hit, key=_rank_key)
            perm_md.append(
                f"First combo at/above ~50% WR (by sumR among hits): **{best_hit.variant}** "
                f"WR={best_hit.wr_pct:.1f}% sumR={best_hit.sum_r:+.1f}."
            )
        else:
            best_wr = max(perm_totals, key=lambda t: (t.wr_pct, t.sum_r))
            perm_md.append(
                f"**No combo reached ~50% WR.** Highest book WR: **{best_wr.variant}** "
                f"{best_wr.wr_pct:.1f}% / sumR={best_wr.sum_r:+.1f} — still ~{50 - best_wr.wr_pct:.0f}pp short of the target "
                f"and only +{best_wr.wr_pct - 32.9:.1f}pp vs the old 32.9% full-trail book."
            )
        # XAU+US100 long-only (Adam's focus names)
        for tag, vname in (("M45", "ha_m45_longonly"), ("H1", "ha_h1_longonly")):
            xau = next((c for c in perm_cells if c.variant == vname and c.symbol == "XAU"), None)
            nq = next((c for c in perm_cells if c.variant == vname and c.symbol == "US100"), None)
            if xau and nq:
                n = xau.n + nq.n
                wins = sum(1 for tr in (xau.trades + nq.trades) if tr.r > 0)
                wr = 100.0 * wins / n if n else 0.0
                sr = xau.sum_r + nq.sum_r
                perm_md.append(
                    f"XAU+US100 long-only **{tag}**: n={n} WR={wr:.1f}% sumR={sr:+.1f} "
                    f"(XAU {xau.wr_pct:.1f}% / {xau.sum_r:+.1f}, "
                    f"US100 {nq.wr_pct:.1f}% / {nq.sum_r:+.1f})."
                )
        perm_md.append("")

    section = []
    section.append(HA_SECTION)
    section.append("")
    section.append(
        f"Generated `{payload['ha_exit']['generated_at']}`. Same 12m data, slow **144**, M5 band-cross, "
        "M45 structure gate **ON**, cap 2, both sides unless a WR perm says otherwise."
    )
    section.append("")
    section.append("**Locked runner (this retest):** 50% at 1:2 → remaining stop to **entry (BE)** → "
                   "full exit on confirmed **M5 HA colour flip** against the position (body close), or BE/stop. "
                   "ST is bias + initial SL only. No M45 slow-band, no ST-line trail, ST-flip **OFF**.")
    section.append("")
    section.append("| cell | ST TF | exit |")
    section.append("| --- | --- | --- |")
    section.append("| **A** `ha_m45` | M45 | half + BE + M5 HA flip |")
    section.append("| **B** `ha_h1` | H1 | half + BE + M5 HA flip |")
    section.append("| **C** `baseline_144` | M45 | **OLD** full-trail (reference only — not this lock) |")
    section.append("")
    section.append("Win = total R > 0 (half at +2R books +1R; BE runner = 0 → trade ≈ +1R win). "
                   "Loss = stopped before TP1 (−1R) or net R ≤ 0. Same-bar stop beats TP1.")
    section.append("")
    section.append("### Leaderboard — book")
    section.append("")
    section.append(_md_table(hdr, book_rows))
    section.append("")
    section.append("### Per symbol — sumR / WR (A vs B; C = OLD exit)")
    section.append("")
    section.append(
        _md_table(
            ["symbol", "A sumR", "B sumR", "C OLD sumR", "A WR", "B WR", "C OLD WR", "HA-exit winner"],
            wide,
        )
    )
    section.append("")
    section.append("### Detail")
    section.append("")
    section.extend(detail)
    section.append("### Call")
    section.append("")
    section.append(rec)
    section.append("")
    section.extend(perm_md)

    text = OUT_MD.read_text() if OUT_MD.exists() else ""
    howto = (
        "## How to rerun\n\n"
        "```bash\n"
        "python3 -m tools.ha_hunt_st_compare.run_bakeoff\n"
        "python3 -m tools.ha_hunt_st_compare.run_bakeoff --h1-followup\n"
        "python3 -m tools.ha_hunt_st_compare.run_bakeoff --ha-exit\n"
        "python3 -m tools.ha_hunt_st_compare.run_bakeoff --m15\n"
        "```\n\n"
        "With Capital DEMO env (`CAPITAL_API_KEY`, `CAPITAL_EMAIL`, `CAPITAL_API_PASSWORD`) the loader prefers Capital mid M5.\n"
        "Caches live under `tools/ha_hunt_st_compare/cache/` (gitignored).\n"
    )
    if HA_SECTION in text:
        pre = text.split(HA_SECTION)[0].rstrip()
        text = pre + "\n\n" + "\n".join(section) + "\n" + howto
    else:
        how = "## How to rerun"
        if how in text:
            pre = text.split(how)[0].rstrip()
            text = pre + "\n\n" + "\n".join(section) + "\n" + howto
        else:
            text = text.rstrip() + "\n\n" + "\n".join(section) + "\n" + howto

    OUT_MD.write_text(text if text.endswith("\n") else text + "\n")
    OUT_JSON.write_text(json.dumps(payload, indent=2) + "\n")
    print(f"wrote {OUT_MD}")
    print(f"wrote {OUT_JSON}")
    print("HA-EXIT REC", rec)
    return 0


M15_LABELS = {
    "m15_m45": "A M15 + M45 ST + half TP1 + BE + M15 HA flip",
    "m15_h1": "B M15 + H1 ST + half TP1 + BE + M15 HA flip",
    "m15_m45_full": "C M15 + M45 ST full-trail (no half, ST trail)",
}
M15_HA_ORDER = ["m15_m45", "m15_h1"]
M15_SECTION = "## M15 HA-flip runner (vs M5)"


def _row_to_book(row: dict, symbol: str, variant: str) -> Book:
    b = Book(symbol=symbol, variant=variant)
    b.n = int(row.get("n") or 0)
    b.wr_pct = float(row.get("wr_pct") or 0)
    b.wr_full_pct = float(row.get("wr_full_pct") or b.wr_pct)
    b.sum_r = float(row.get("sum_r") or 0)
    b.avg_r = float(row.get("avg_r") or 0)
    b.max_dd_r = float(row.get("max_dd_r") or 0)
    b.pf = float(row.get("pf") or 0)
    b.n_long = int(row.get("n_long") or 0)
    b.n_short = int(row.get("n_short") or 0)
    b.n_tp1 = int(row.get("n_tp1") or 0)
    b.exits = dict(row.get("exits") or {})
    return b


def run_m15_ha_exit() -> int:
    """M15 analogy of half+BE+HA. Compare to M5 numbers already in the JSON."""
    print(f"M15 HA-flip runner  {START.isoformat()} → {END.isoformat()}", flush=True)
    bars5, load_errors = _load_bars()
    if not bars5:
        return 1
    bars15 = {}
    for sym, b in bars5.items():
        m15 = resample_bars(b, 15)
        bars15[sym] = m15
        print(f"  {sym:7} M15 n={len(m15.close)}  {m15.meta.first} → {m15.meta.last}", flush=True)

    cells = _run_params(m15_ha_exit_variants(), bars15)
    totals = _totals(cells)
    by_name = {t.variant: t for t in totals}

    payload = json.loads(OUT_JSON.read_text()) if OUT_JSON.exists() else {}
    m5 = payload.get("ha_exit") or {}
    m5_book = {k: _row_to_book(v, "BOOK", k) for k, v in (m5.get("book") or {}).items()}
    m5_by_sym: dict[str, dict[str, Book]] = {}
    for sym, block in (m5.get("by_symbol") or {}).items():
        m5_by_sym[sym] = {k: _row_to_book(v, sym, k) for k, v in block.items()}

    def cell_of(name: str, sym: str) -> Book:
        return next(b for b in cells if b.variant == name and b.symbol == sym)

    def metrics_row(b: Book, label: str) -> list[str]:
        tp1_pct = (100.0 * b.n_tp1 / b.n) if b.n else 0.0
        return [
            label,
            str(b.n),
            f"{b.wr_pct:.1f}",
            f"{tp1_pct:.1f}",
            f"{b.sum_r:.2f}",
            f"{b.avg_r:.3f}",
            f"{b.max_dd_r:.2f}",
            _fmt_pf(b.pf),
        ]

    hdr = ["cell", "n", "WR% (R>0)", "TP1%", "sumR", "avgR", "maxDD(R)", "PF"]

    by_symbol = {sym: {name: _book_row(cell_of(name, sym)) for name in M15_LABELS} for sym in bars15}
    a, b = by_name["m15_m45"], by_name["m15_h1"]
    full = by_name.get("m15_m45_full")

    def m5_cell(name: str, sym: str | None = None) -> Book | None:
        if sym is None or sym == "BOOK":
            return m5_book.get(name)
        return (m5_by_sym.get(sym) or {}).get(name)

    xau_a, xau_b = cell_of("m15_m45", "XAU"), cell_of("m15_h1", "XAU")
    nq_a, nq_b = cell_of("m15_m45", "US100"), cell_of("m15_h1", "US100")
    m5_xau_a, m5_xau_b = m5_cell("ha_m45", "XAU"), m5_cell("ha_h1", "XAU")
    m5_nq_a, m5_nq_b = m5_cell("ha_m45", "US100"), m5_cell("ha_h1", "US100")
    m5_a, m5_b = m5_cell("ha_m45"), m5_cell("ha_h1")

    closer = []
    for label, m15b, m5b in (
        ("XAU A M45", xau_a, m5_xau_a),
        ("XAU B H1", xau_b, m5_xau_b),
        ("US100 A M45", nq_a, m5_nq_a),
        ("US100 B H1", nq_b, m5_nq_b),
    ):
        if m5b:
            closer.append(f"{label}: M15 {m15b.wr_pct:.1f}% vs M5 {m5b.wr_pct:.1f}% ({m15b.wr_pct - m5b.wr_pct:+.1f}pp)")
    rec = (
        f"M15 HA-exit book: A (M45 ST) n={a.n} WR={a.wr_pct:.1f}% sumR={a.sum_r:+.1f}; "
        f"B (H1 ST) n={b.n} WR={b.wr_pct:.1f}% sumR={b.sum_r:+.1f}. "
        + (" ".join(closer) + ". " if closer else "")
        + (
            f"XAU/US100 vs ~50%: A XAU {xau_a.wr_pct:.1f}% US100 {nq_a.wr_pct:.1f}%; "
            f"B XAU {xau_b.wr_pct:.1f}% US100 {nq_b.wr_pct:.1f}%."
        )
    )

    payload["m15_ha_exit"] = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "cells": {
            "A": "M15 trigger + M45 ST bias/SL + 50% TP1 + BE + M15 HA flip",
            "B": "M15 trigger + H1 ST bias/SL + 50% TP1 + BE + M15 HA flip",
            "C": "M15 trigger + M45 ST full-trail (optional old-exit analogy)",
        },
        "rules": {
            "chart": "M15",
            "structure": "M45 large RMA 144/33, gate ON",
            "trigger": "first M15 close beyond M15 fast band",
            "runner": "50% at 1:2, stop → BE, confirmed M15 HA colour flip or BE",
        },
        "coverage": coverage_table(bars5),
        "load_errors": load_errors,
        "m15_n": {sym: int(len(bars15[sym].close)) for sym in bars15},
        "by_symbol": by_symbol,
        "book": {name: _book_row(by_name[name]) for name in M15_LABELS},
        "recommendation": rec,
    }

    book_rows = [metrics_row(by_name[n], M15_LABELS[n]) for n in list(M15_LABELS)]
    vs_rows = []
    for sym in list(bars15) + ["BOOK"]:
        m15a = by_name["m15_m45"] if sym == "BOOK" else cell_of("m15_m45", sym)
        m15b = by_name["m15_h1"] if sym == "BOOK" else cell_of("m15_h1", sym)
        m5a = m5_cell("ha_m45", None if sym == "BOOK" else sym)
        m5b = m5_cell("ha_h1", None if sym == "BOOK" else sym)
        vs_rows.append(
            [
                sym,
                f"{m15a.wr_pct:.1f}",
                f"{m5a.wr_pct:.1f}" if m5a else "—",
                f"{m15b.wr_pct:.1f}",
                f"{m5b.wr_pct:.1f}" if m5b else "—",
                f"{m15a.sum_r:.2f}",
                f"{m5a.sum_r:.2f}" if m5a else "—",
                f"{m15b.sum_r:.2f}",
                f"{m5b.sum_r:.2f}" if m5b else "—",
                f"{(100.0 * m15a.n_tp1 / m15a.n) if m15a.n else 0:.1f}",
                f"{(100.0 * m15b.n_tp1 / m15b.n) if m15b.n else 0:.1f}",
            ]
        )

    detail = []
    for sym in bars15:
        detail.append(f"#### {sym}")
        detail.append("")
        rows = [metrics_row(cell_of(n, sym), M15_LABELS[n]) for n in M15_LABELS]
        detail.append(_md_table(hdr, rows))
        detail.append("")
        a_s, b_s = cell_of("m15_m45", sym), cell_of("m15_h1", sym)
        detail.append(
            f"Exits A: `{a_s.exits}` · B: `{b_s.exits}`. "
            f"TP1 A {a_s.n_tp1}/{a_s.n} · B {b_s.n_tp1}/{b_s.n}."
        )
        detail.append("")

    section = []
    section.append(M15_SECTION)
    section.append("")
    section.append(
        f"Generated `{payload['m15_ha_exit']['generated_at']}`. Same 12m data resampled M5→M15 (epoch buckets). "
        "Slow **144**, M15 band-cross, M45 structure gate **ON**, cap 2, both sides."
    )
    section.append("")
    section.append(
        "**Locked analogy:** trigger = first M15 close beyond the M15 fast RMA band. "
        "Bias+SL = closed M45 ST (A) or H1 ST (B). "
        "50% at 1:2 → runner stop to **entry** → full exit on confirmed **M15 HA colour flip** (body) or BE. "
        "ST is bias + initial SL only."
    )
    section.append("")
    section.append("### M15 book")
    section.append("")
    section.append(_md_table(hdr, book_rows))
    section.append("")
    section.append("### Side-by-side vs M5 half+BE+HA")
    section.append("")
    section.append(
        _md_table(
            [
                "symbol",
                "M15 A WR",
                "M5 A WR",
                "M15 B WR",
                "M5 B WR",
                "M15 A sumR",
                "M5 A sumR",
                "M15 B sumR",
                "M5 B sumR",
                "M15 A TP1%",
                "M15 B TP1%",
            ],
            vs_rows,
        )
    )
    section.append("")
    section.append("### Detail (M15)")
    section.append("")
    section.extend(detail)
    section.append("### Call")
    section.append("")
    section.append(rec)
    section.append("")
    hit50 = any(x.wr_pct >= 49.5 for x in (xau_a, xau_b, nq_a, nq_b))
    section.append(
        "M15 vs ~50% WR on XAU/US100: "
        + ("at least one cell is near/at 50%." if hit50 else "**still well below ~50%** — same TP1-rate ceiling as M5.")
    )
    if m5_a and m5_b:
        section.append(
            f"Book WR: M15 A {a.wr_pct:.1f}% vs M5 A {m5_a.wr_pct:.1f}% ({a.wr_pct - m5_a.wr_pct:+.1f}pp); "
            f"M15 B {b.wr_pct:.1f}% vs M5 B {m5_b.wr_pct:.1f}% ({b.wr_pct - m5_b.wr_pct:+.1f}pp)."
        )
    if full:
        section.append(
            f"Optional M15 full-trail (C): n={full.n} WR={full.wr_pct:.1f}% sumR={full.sum_r:+.1f} "
            f"avgR={full.avg_r:.3f} DD={full.max_dd_r:.1f} PF={_fmt_pf(full.pf)}."
        )
    section.append("")

    text = OUT_MD.read_text() if OUT_MD.exists() else ""
    howto = (
        "## How to rerun\n\n"
        "```bash\n"
        "python3 -m tools.ha_hunt_st_compare.run_bakeoff\n"
        "python3 -m tools.ha_hunt_st_compare.run_bakeoff --h1-followup\n"
        "python3 -m tools.ha_hunt_st_compare.run_bakeoff --ha-exit\n"
        "python3 -m tools.ha_hunt_st_compare.run_bakeoff --m15\n"
        "```\n\n"
        "With Capital DEMO env (`CAPITAL_API_KEY`, `CAPITAL_EMAIL`, `CAPITAL_API_PASSWORD`) the loader prefers Capital mid M5.\n"
        "Caches live under `tools/ha_hunt_st_compare/cache/` (gitignored).\n"
    )
    if M15_SECTION in text:
        pre = text.split(M15_SECTION)[0].rstrip()
        text = pre + "\n\n" + "\n".join(section) + "\n" + howto
    elif "## How to rerun" in text:
        pre = text.split("## How to rerun")[0].rstrip()
        text = pre + "\n\n" + "\n".join(section) + "\n" + howto
    else:
        text = text.rstrip() + "\n\n" + "\n".join(section) + "\n" + howto

    OUT_MD.write_text(text if text.endswith("\n") else text + "\n")
    OUT_JSON.write_text(json.dumps(payload, indent=2) + "\n")
    print(f"wrote {OUT_MD}")
    print(f"wrote {OUT_JSON}")
    print("M15 REC", rec)
    return 0


if __name__ == "__main__":
    if "--pyramid-ab" in sys.argv:
        from tools.ha_hunt_st_compare.run_pyramid_ab import main as run_pyramid_ab

        raise SystemExit(run_pyramid_ab())
    if "--m15" in sys.argv:
        raise SystemExit(run_m15_ha_exit())
    if "--ha-exit" in sys.argv:
        raise SystemExit(run_ha_exit())
    if "--h1-followup" in sys.argv:
        raise SystemExit(run_h1_followup())
    raise SystemExit(main())
