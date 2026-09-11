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
from tools.ha_hunt_st_compare.simulator import Book, Params, variants, simulate

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


if __name__ == "__main__":
    raise SystemExit(main())
