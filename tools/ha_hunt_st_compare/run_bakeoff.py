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

from tools.ha_hunt_st_compare.ohlc import SYMBOLS, coverage_table, load_symbol
from tools.ha_hunt_st_compare.simulator import Book, Params, h1_compare_variants, variants, simulate

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


def run_h1_followup() -> int:
    """Compare locked M45-ST full-TP1 vs H1-ST + 50% TP1 (and H1-ST full trail)."""
    print(f"H1 follow-up  {START.isoformat()} → {END.isoformat()}", flush=True)
    bars, load_errors = _load_bars()
    if not bars:
        return 1
    cells = _run_params(h1_compare_variants(), bars)
    totals = _totals(cells)
    by_name = {t.variant: t for t in totals}

    payload = {}
    if OUT_JSON.exists():
        payload = json.loads(OUT_JSON.read_text())
    payload["h1_followup"] = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "rules": {
            "keep": "M5 band-cross, M45 RMA structure slow=144, gate ON, cap 2, both sides",
            "st": "closed H1 Supertrend = bias AND default SL (same line); 1R = |entry − H1 ST|",
            "tp1": "50% off at +2R (books +1R from that half); remaining 50% trails H1 ST (never widen)",
            "runner_exit": "trail hit / H1 ST flip / M45 slow-band body",
            "same_bar": "stop wins vs TP1 (no scale-out on that bar)",
            "isolation": "h1st_full = H1 ST but full position after TP1 touch (no half-close)",
        },
        "coverage": coverage_table(bars),
        "load_errors": load_errors,
        "by_symbol": {
            name: [_book_row(b) for b in sorted([c for c in cells if c.variant == name], key=_rank_key, reverse=True)]
            for name in ("baseline_144", "h1st_partial", "h1st_full")
        },
        "book": {name: _book_row(by_name[name]) for name in by_name},
        "recommendation": None,
    }

    hdr = ["name", "n", "WR%", "sumR", "avgR", "maxDD(R)", "PF", "stop", "trail", "st_flip", "slow", "eod", "L/S", "TP1"]

    def block(name: str) -> str:
        rows = [c for c in cells if c.variant == name]
        rows = sorted(rows, key=_rank_key, reverse=True)
        book = by_name[name]
        return _md_table(hdr, [_h1_book_cells(b) for b in rows] + [_h1_book_cells(book)])

    h1p = by_name["h1st_partial"]
    h1f = by_name["h1st_full"]
    m45 = by_name["baseline_144"]

    # Per-ticker: does 50% scale help vs H1 full, and vs M45 locked?
    ticker_lines = []
    compare_rows = []
    for sym in bars:
        m = next(b for b in cells if b.variant == "baseline_144" and b.symbol == sym)
        p_ = next(b for b in cells if b.variant == "h1st_partial" and b.symbol == sym)
        f = next(b for b in cells if b.variant == "h1st_full" and b.symbol == sym)
        scale_delta = p_.sum_r - f.sum_r
        vs_m45 = p_.sum_r - m.sum_r
        tf_delta = f.sum_r - m.sum_r
        compare_rows.append(
            [
                sym,
                f"{m.sum_r:.2f}",
                f"{f.sum_r:.2f}",
                f"{p_.sum_r:.2f}",
                f"{tf_delta:+.2f}",
                f"{scale_delta:+.2f}",
                f"{vs_m45:+.2f}",
                f"{p_.wr_pct:.1f}",
                f"{p_.max_dd_r:.1f}",
            ]
        )
        if scale_delta > 0.5:
            ticker_lines.append(f"{sym}: 50% scale helps H1 (Δ {scale_delta:+.1f}R vs H1-full).")
        elif scale_delta < -0.5:
            ticker_lines.append(f"{sym}: 50% scale hurts H1 (Δ {scale_delta:+.1f}R vs H1-full).")
        else:
            ticker_lines.append(f"{sym}: 50% scale ≈ H1-full (Δ {scale_delta:+.1f}R).")

    rec = (
        f"H1-ST + 50% TP1 book sumR={h1p.sum_r:.2f} PF={_fmt_pf(h1p.pf)} n={h1p.n} DD={h1p.max_dd_r:.1f} "
        f"vs locked M45-ST full-TP1 sumR={m45.sum_r:.2f} PF={_fmt_pf(m45.pf)} n={m45.n} DD={m45.max_dd_r:.1f} "
        f"vs H1-ST full-trail sumR={h1f.sum_r:.2f} PF={_fmt_pf(h1f.pf)}. "
        + " ".join(ticker_lines)
    )
    if h1p.sum_r > m45.sum_r and h1p.pf >= m45.pf * 0.95:
        rec += " Prefer H1-ST + 50% TP1 over locked M45-ST on this sample."
    elif m45.sum_r > h1p.sum_r:
        rec += " Locked M45-ST still wins the book — keep M45 ST as default; H1 is not an upgrade."
    else:
        rec += " Mixed: inspect per-ticker before switching the Pine bias/SL to H1."
    payload["h1_followup"]["recommendation"] = rec

    section = []
    section.append("## H1 Supertrend + 50% TP1 (follow-up)")
    section.append("")
    section.append(
        f"Generated `{payload['h1_followup']['generated_at']}`. Same universe / window / M5 trigger / "
        "M45 structure gate ON / slow 144 / cap 2 / both sides. Only the Supertrend TF and TP1 sizing change."
    )
    section.append("")
    section.append("| cell | bias + SL | TP1 |")
    section.append("| --- | --- | --- |")
    section.append("| `baseline_144` | closed **M45** ST | full position; TP1 only arms the trail (locked Pine) |")
    section.append("| `h1st_partial` | closed **H1** ST | **50%** off at +2R (+1R booked), 50% trails H1 ST |")
    section.append("| `h1st_full` | closed **H1** ST | full position after TP1 touch (isolates TF vs scale) |")
    section.append("")
    section.append("1R = |entry − that ST line|. Runner never widens. Conservative same-bar: stop before TP1 (no scale-out).")
    section.append("After TP1, `trail` = runner hit the ratcheted ST; `stop` = full SL before TP1.")
    section.append("")
    section.append("### Locked M45-ST (re-run, full-TP1 conceptual)")
    section.append("")
    section.append(block("baseline_144"))
    section.append("")
    section.append("### H1-ST + 50% TP1 / 50% trail")
    section.append("")
    section.append(block("h1st_partial"))
    section.append("")
    section.append("### H1-ST full position after TP1 (isolation)")
    section.append("")
    section.append(block("h1st_full"))
    section.append("")
    section.append("### A/B per ticker (sumR)")
    section.append("")
    section.append(
        _md_table(
            ["symbol", "M45 full", "H1 full", "H1 50%", "Δ TF (H1f−M45)", "Δ scale (50%−H1f)", "Δ vs locked (50%−M45)", "H1 50% WR%", "H1 50% DD"],
            compare_rows,
        )
    )
    section.append("")
    section.append("### H1 vs M45 recommendation")
    section.append("")
    section.append(rec)
    section.append("")
    if h1p.sum_r >= m45.sum_r:
        section.append("On this sample the H1 book is at least as good as locked M45-ST. Still confirm on Capital mids before changing Pine defaults.")
    else:
        section.append("On this sample **keep M45 Supertrend** as the locked bias+SL. Switching to H1 does not pay for the book.")
    section.append("")

    text = OUT_MD.read_text() if OUT_MD.exists() else ""
    mark = "## H1 Supertrend + 50% TP1 (follow-up)"
    how = "## How to rerun"
    if mark in text:
        pre = text.split(mark)[0].rstrip()
        post = ""
        if how in text.split(mark, 1)[1]:
            post = how + text.split(mark, 1)[1].split(how, 1)[1]
        else:
            post = (
                "\n## How to rerun\n\n"
                "```bash\n"
                "python3 -m tools.ha_hunt_st_compare.run_bakeoff\n"
                "python3 -m tools.ha_hunt_st_compare.run_bakeoff --h1-followup\n"
                "```\n"
            )
        text = pre + "\n\n" + "\n".join(section) + "\n" + post.lstrip()
    elif how in text:
        pre, post = text.split(how, 1)
        howto = (
            "## How to rerun\n\n"
            "```bash\n"
            "python3 -m tools.ha_hunt_st_compare.run_bakeoff\n"
            "python3 -m tools.ha_hunt_st_compare.run_bakeoff --h1-followup\n"
            "```\n"
            + (post.split("```", 2)[-1] if False else "\nWith Capital DEMO env (`CAPITAL_API_KEY`, `CAPITAL_EMAIL`, `CAPITAL_API_PASSWORD`) the loader prefers Capital mid M5.\nCaches live under `tools/ha_hunt_st_compare/cache/` (gitignored).\n")
        )
        text = pre.rstrip() + "\n\n" + "\n".join(section) + "\n" + howto
    else:
        text = text.rstrip() + "\n\n" + "\n".join(section) + "\n"

    OUT_MD.write_text(text if text.endswith("\n") else text + "\n")
    OUT_JSON.write_text(json.dumps(payload, indent=2) + "\n")
    print(f"wrote {OUT_MD}")
    print(f"wrote {OUT_JSON}")
    print("H1 REC", rec)
    return 0


if __name__ == "__main__":
    if "--h1-followup" in sys.argv:
        raise SystemExit(run_h1_followup())
    raise SystemExit(main())
