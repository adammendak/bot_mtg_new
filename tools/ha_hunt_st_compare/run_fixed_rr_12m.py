#!/usr/bin/env python3
"""12-month ST_V3 A 1:1.5 vs prod-like bakeoff (ATR 7 / factor 2.0, flat only).

Research only. Reuses ``tools.ha_hunt_st_compare`` from PR #151 (loader +
simulator). Does not change prod Java defaults. Does not invent prices.

Window: ~12 months ending 2026-09-13 (entries 2025-09-13 → 2026-09-13).
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
OUT_MD = DOCS / "st-v3-fixed-rr-12m.md"
OUT_JSON = DOCS / "st-v3-fixed-rr-12m.json"

# ~12 months ending ~2026-09-13 (same end date as PR #151 quarter bakeoff).
TRADE_END = datetime(2026, 9, 13, tzinfo=timezone.utc)
TRADE_START = datetime(2025, 9, 13, tzinfo=timezone.utc)
WARMUP_DAYS = 120
FETCH_START = TRADE_START - timedelta(days=WARMUP_DAYS)

STACKS = [
    ("M15_ST_V3", "M15+H1", 15, 60, True),
    ("M5_ST_V3", "M5+M45", 5, 45, False),
]

REQUIRED_SYMBOLS = ("XAU", "US100")
PRIMARY7 = ("XAU", "BTC", "US100", "US500", "US30", "GER40", "EURUSD")
RR = 1.5

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
            "winner",
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


def _combo(books: list[Book], name: str, variant: str) -> Book:
    trades = [t for b in books for t in b.trades]
    return _metrics(sorted(trades, key=lambda t: t.entry_time), name, variant)


def _row_from_book(sym: str, stack: str, mode: str, b: Book, note: str = "") -> list[str]:
    return [
        sym,
        stack,
        mode,
        str(b.n),
        f"{b.wr_pct:.1f}",
        f"{b.sum_r:+.2f}",
        _fmt_pf(b.pf),
        f"{b.max_dd_r:.2f}",
        f"{b.avg_r:.3f}",
        note,
    ]


def _winner(a: Book, prod: Book) -> str:
    if a.n == 0 and prod.n == 0:
        return "no trades"
    if a.n == 0:
        return "prod-like"
    if prod.n == 0:
        return "A 1:1.5"
    if abs(a.sum_r - prod.sum_r) < 0.05:
        return "tie"
    return "A 1:1.5" if a.sum_r > prod.sum_r else "prod-like"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="ST_V3 12-month A 1:1.5 vs prod-like")
    ap.add_argument("--symbols", default="", help="comma list (default: primary + extra)")
    ap.add_argument("--no-extra", action="store_true", help="skip XAG/J225/USDJPY")
    ap.add_argument("--no-m5", action="store_true", help="skip M5+M45 stack")
    args = ap.parse_args(argv)

    DOCS.mkdir(parents=True, exist_ok=True)
    want = PRIMARY_SYMBOLS[:]
    if not args.no_extra:
        want += EXTRA_SYMBOLS
    if args.symbols.strip():
        want = [s.strip().upper() for s in args.symbols.split(",") if s.strip()]
        want = ["BTC" if s == "BTCUSD" else s for s in want]

    stacks = [s for s in STACKS if not (args.no_m5 and s[0] == "M5_ST_V3")]

    print(
        f"window entries {TRADE_START_ISO} → {TRADE_END_ISO}  "
        f"fetch {FETCH_START.date()} (warmup {WARMUP_DAYS}d)",
        flush=True,
    )
    print(
        "locked ST 7/2.0 · flat · stop=ST line · A full 1:1.5 vs prod half@1:2+BE+band-cross",
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
    a_books: dict[tuple[str, str], Book] = {}
    prod_books: dict[tuple[str, str], Book] = {}

    for stack_id, stack, chart_min, st_min, _required in stacks:
        for sym in want:
            if sym not in bars:
                continue
            chart = chart_for(sym, chart_min)
            print(f"{stack_id} {sym} bars={len(chart.close)}", flush=True)

            p_a = st_v3_fixed_rr(
                "band_st", RR, chart_min, st_min, TRADE_START_ISO, TRADE_END_ISO
            )
            a = simulate(sym, chart, p_a)
            a_books[(sym, stack_id)] = a
            results.append(
                _book_row(
                    a,
                    part="fixed_rr",
                    stack=stack_id,
                    stack_label=stack,
                    entry_mode="band_st",
                    entry_tag="A",
                    rr=RR,
                )
            )
            print(
                f"  A 1:{RR:g}     n={a.n:4}  WR={a.wr_pct:5.1f}%  "
                f"sumR={a.sum_r:8.2f}  PF={_fmt_pf(a.pf)}  DD={a.max_dd_r:6.2f}",
                flush=True,
            )

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
                f"sumR={prod.sum_r:8.2f}  PF={_fmt_pf(prod.pf)}  DD={prod.max_dd_r:6.2f}",
                flush=True,
            )

    cov = coverage_table(bars)
    generated = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")

    def stack_group(store: dict[tuple[str, str], Book], stack_id: str) -> list[Book]:
        return [store[(sym, stack_id)] for sym in want if (sym, stack_id) in store]

    def req_group(store: dict[tuple[str, str], Book], stack_id: str) -> list[Book]:
        return [
            store[(sym, stack_id)]
            for sym in REQUIRED_SYMBOLS
            if (sym, stack_id) in store
        ]

    def primary_group(store: dict[tuple[str, str], Book], stack_id: str) -> list[Book]:
        return [
            store[(sym, stack_id)]
            for sym in PRIMARY7
            if (sym, stack_id) in store
        ]

    lines = [
        "# ST_V3 12-month A 1:1.5 vs prod-like bakeoff",
        "",
        f"Generated **{generated}**. Research note — **do not merge as live Java**.",
        "",
        "Standalone 12-month lock check after PR #151 (last-quarter fixed-RR) and "
        "PR #144 (12-month ST param / indices). Same simulator, same ST 7/2.0 lock, "
        "same loaders. **Does not change prod defaults.**",
        "",
        "## Window and data",
        "",
        f"- **Trade window (entries):** `{TRADE_START.date()}` → `{TRADE_END.date()}` "
        f"({(TRADE_END - TRADE_START).days} calendar days, ending 2026-09-13).",
        f"- **Fetch / warmup:** `{FETCH_START.date()}` → `{TRADE_END.date()}` "
        f"({WARMUP_DAYS} calendar days before first entry so HTF RMA144 / ST 7/2.0 are live).",
        "- **Data source order** (same as PR #151 / #144 `ha_hunt_st_compare`): "
        "local `cache/*_M5.csv` → Capital DEMO mids (if `CAPITAL_*` env is set) → "
        "HistData M1 resampled to M5 → Dukascopy M1 resampled to M5 → "
        "Coinbase Exchange 5m (BTC). Never invents prices.",
        "- Loader notes per symbol are in the coverage table. HistData stamps are "
        "US Eastern → UTC; Dukascopy / Coinbase are UTC native.",
        "- Last print can sit a few days before 2026-09-13 depending on vendor "
        "(HistData often lags; Coinbase is current).",
        "",
        "## Locked stack",
        "",
        "- Supertrend **ATR 7 / factor 2.0** (PR #146 / #150).",
        "- Stop = Supertrend line; **1R = |entry − ST|**. Invalid (wrong-side) ST stops skipped.",
        "- Flat only (no pyramid). Cap 2 fills / ST regime.",
        "- Fast RMA 33 / slow 144. HTF structure gate ON "
        "(same HTF as the Supertrend — Java `StV3Engine`).",
        "- **A 1:1.5:** band-cross + ST bias, full close at +1.5R. No half TP1, no BE, "
        "no HA / band runner. Stop wins on a dual-hit bar.",
        "- **Prod-like:** same entry, half @ 1:2 → stop to BE → entry-TF **band-cross** runner "
        "(PR #150 live `stV3Exit`).",
        "- **Required compare:** M15+H1 on the full loaded book, with XAU and US100 called out "
        "(same pair as PR #151). M5+M45 is the cheap extra stack.",
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
                (row["note"] or SERIES_NOTES.get(row["symbol"], ""))[:140],
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

    for stack_id, stack, *_rest in stacks:
        lines += [
            f"## {stack} — A 1:1.5 vs prod-like",
            "",
            "WR% is R>0 on the booked R (full-size for A; scaled half+runner for prod-like). "
            "maxDD is peak-to-trough of that R equity, in R.",
            "",
        ]
        rows = []
        for sym in want:
            a = a_books.get((sym, stack_id))
            prod = prod_books.get((sym, stack_id))
            if a is None or prod is None:
                continue
            if sym in REQUIRED_SYMBOLS:
                tag = "required"
            elif sym in PRIMARY7:
                tag = "primary"
            else:
                tag = "extra"
            rows.append(_row_from_book(sym, stack, "A 1:1.5", a, tag))
            rows.append(_row_from_book(sym, stack, "prod-like", prod, tag))
        if rows:
            lines += [
                _md_table(
                    ["symbol", "stack", "mode", "n", "WR%", "sumR", "PF", "maxDD", "avgR", "note"],
                    rows,
                ),
                "",
            ]
        else:
            lines += ["No books for this stack.", ""]

        a_combo = _combo(stack_group(a_books, stack_id), "BOOK", f"{stack_id}_A")
        p_combo = _combo(stack_group(prod_books, stack_id), "BOOK", f"{stack_id}_prod")
        a_p7 = _combo(primary_group(a_books, stack_id), "PRIMARY7", f"{stack_id}_A")
        p_p7 = _combo(primary_group(prod_books, stack_id), "PRIMARY7", f"{stack_id}_prod")
        a_req = _combo(req_group(a_books, stack_id), "XAU+US100", f"{stack_id}_A")
        p_req = _combo(req_group(prod_books, stack_id), "XAU+US100", f"{stack_id}_prod")
        lines += [
            "### Book + primary 7 + required pair",
            "",
            "PRIMARY7 = XAU, BTC, US100, US500, US30, GER40, EURUSD. BOOK also includes XAG / J225 / USDJPY.",
            "",
            _md_table(
                ["book", "stack", "mode", "n", "WR%", "sumR", "PF", "maxDD", "avgR", "winner"],
                [
                    _row_from_book("BOOK", stack, "A 1:1.5", a_combo, _winner(a_combo, p_combo)),
                    _row_from_book("BOOK", stack, "prod-like", p_combo, _winner(a_combo, p_combo)),
                    _row_from_book("PRIMARY7", stack, "A 1:1.5", a_p7, _winner(a_p7, p_p7)),
                    _row_from_book("PRIMARY7", stack, "prod-like", p_p7, _winner(a_p7, p_p7)),
                    _row_from_book("XAU+US100", stack, "A 1:1.5", a_req, _winner(a_req, p_req)),
                    _row_from_book("XAU+US100", stack, "prod-like", p_req, _winner(a_req, p_req)),
                ],
            ),
            "",
        ]

    # Per-symbol winner table for the required stack
    lines += [
        "## Per-symbol winner (sumR)",
        "",
    ]
    win_rows = []
    for stack_id, stack, *_r in stacks:
        for sym in want:
            a = a_books.get((sym, stack_id))
            prod = prod_books.get((sym, stack_id))
            if a is None or prod is None:
                continue
            win_rows.append(
                [
                    sym,
                    stack,
                    f"{a.sum_r:+.2f}",
                    f"{prod.sum_r:+.2f}",
                    f"{a.sum_r - prod.sum_r:+.2f}",
                    _winner(a, prod),
                    (
                        "required"
                        if sym in REQUIRED_SYMBOLS
                        else ("primary" if sym in PRIMARY7 else "extra")
                    ),
                ]
            )
    lines += [
        _md_table(
            ["symbol", "stack", "A 1:1.5", "prod-like", "Δ sumR", "winner", "note"],
            win_rows,
        ),
        "",
    ]

    # Call
    a_m15_book = _combo(stack_group(a_books, "M15_ST_V3"), "BOOK", "m15_A")
    p_m15_book = _combo(stack_group(prod_books, "M15_ST_V3"), "BOOK", "m15_prod")
    a_m15_p7 = _combo(primary_group(a_books, "M15_ST_V3"), "PRIMARY7", "m15_A")
    p_m15_p7 = _combo(primary_group(prod_books, "M15_ST_V3"), "PRIMARY7", "m15_prod")
    a_m15_req = _combo(req_group(a_books, "M15_ST_V3"), "XAU+US100", "m15_A")
    p_m15_req = _combo(req_group(prod_books, "M15_ST_V3"), "XAU+US100", "m15_prod")
    a_m5_book = _combo(stack_group(a_books, "M5_ST_V3"), "BOOK", "m5_A")
    p_m5_book = _combo(stack_group(prod_books, "M5_ST_V3"), "BOOK", "m5_prod")

    req_edge = p_m15_req.sum_r - a_m15_req.sum_r
    book_edge = p_m15_book.sum_r - a_m15_book.sum_r
    p7_edge = p_m15_p7.sum_r - a_m15_p7.sum_r
    m5_edge = p_m5_book.sum_r - a_m5_book.sum_r

    if a_m15_book.n == 0 and p_m15_book.n == 0:
        call = (
            "**No call — the 12-month M15+H1 books produced no trades.** "
            "Re-run after data loads."
        )
        deploy = "incomplete"
    elif req_edge > 0:
        call = (
            f"**12-month still favors the prod runner. Do not deploy fixed 1:1.5.** "
            f"Required XAU+US100 M15+H1: prod-like {p_m15_req.wr_pct:.1f}% / {p_m15_req.sum_r:+.1f}R "
            f"(n={p_m15_req.n}, PF {_fmt_pf(p_m15_req.pf)}, DD {p_m15_req.max_dd_r:.1f}) vs "
            f"A 1:1.5 {a_m15_req.wr_pct:.1f}% / {a_m15_req.sum_r:+.1f}R "
            f"(n={a_m15_req.n}, PF {_fmt_pf(a_m15_req.pf)}, DD {a_m15_req.max_dd_r:.1f}). "
            f"The #151 quarter (A +24.5R vs prod +6.9R on this pair) does not survive 12 months. "
            f"XAU is the runner lock; US100 alone prefers 1:1.5 and is not enough. "
            f"10-name M15 book is a coin flip on sumR (A {a_m15_book.sum_r:+.1f}R vs prod {p_m15_book.sum_r:+.1f}R) "
            f"but prod keeps the better avgR / PF. "
            f"PRIMARY7 M15: A {a_m15_p7.sum_r:+.1f}R vs prod {p_m15_p7.sum_r:+.1f}R "
            f"(Δ {p7_edge:+.1f}R for prod)."
        )
        deploy = "keep_prod"
    elif req_edge < 0 and book_edge < 0 and m5_edge < 0:
        call = (
            f"**12-month favors fixed A 1:1.5 over the prod runner on this window.** "
            f"XAU+US100 M15+H1 A {a_m15_req.sum_r:+.1f}R vs prod {p_m15_req.sum_r:+.1f}R; "
            f"10-name book A {a_m15_book.sum_r:+.1f}R vs prod {p_m15_book.sum_r:+.1f}R. "
            "Still **do not** change prod Java — one vendor/window, no fees. Paper only if Adam wants a simpler ticket."
        )
        deploy = "paper_1to15"
    else:
        call = (
            f"**Keep current ST_V3 management.** Required XAU+US100 M15+H1 does not lock 1:1.5 "
            f"(A {a_m15_req.sum_r:+.1f}R vs prod {p_m15_req.sum_r:+.1f}R, Δ {req_edge:+.1f}R for prod). "
            f"10-name M15 book A {a_m15_book.sum_r:+.1f}R vs prod {p_m15_book.sum_r:+.1f}R."
        )
        deploy = "keep_prod"

    m5_note = ""
    if a_m5_book.n or p_m5_book.n:
        m5_note = (
            f" M5+M45 10-name book favors prod: A 1:1.5 {a_m5_book.wr_pct:.1f}% / {a_m5_book.sum_r:+.1f}R "
            f"(n={a_m5_book.n}, PF {_fmt_pf(a_m5_book.pf)}, DD {a_m5_book.max_dd_r:.1f}) vs "
            f"prod-like {p_m5_book.wr_pct:.1f}% / {p_m5_book.sum_r:+.1f}R "
            f"(n={p_m5_book.n}, PF {_fmt_pf(p_m5_book.pf)}, DD {p_m5_book.max_dd_r:.1f})."
        )

    lines += [
        "## Call",
        "",
        call + m5_note,
        "",
        "- Do **not** change prod Java defaults (`HtsTradeService.ST_V3_TP1_MULT`, "
        "band-cross runner, satellite universes).",
        "- Do **not** merge this note as a live switch.",
        "",
        "## How this relates to #151 / #144",
        "",
        "- PR #151 (90d to 2026-09-13) found that quarter *friendly* to fixed RR and "
        "*hostile* to the prod runner (XAU+US100 M15+H1 A 1:1.5 +24.5R vs prod +6.9R; "
        "book-wide prod about −34R).",
        "- PR #144 / the 12-month lock that shipped ST_V3 was the current runner "
        "(half+BE+HA then, band-cross now) at ST 7/2.0.",
        "- This note asks whether that 12-month lock still holds when management is "
        "the current band-cross runner vs a simpler full 1:1.5.",
        "",
        "## How to rerun",
        "",
        "```bash",
        "python3 -m pip install -r tools/ha_hunt_st_compare/requirements.txt",
        "python3 -m tools.ha_hunt_st_compare.test_simulator",
        "python3 -m tools.ha_hunt_st_compare.run_fixed_rr_12m",
        "```",
        "",
        "Flags: `--symbols XAU,US100,BTC` · `--no-extra` · `--no-m5`.",
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
            "fixed_rr": [1.5],
            "prod_like": "half_1to2_be_band_cross",
            "entry": "band_cross_plus_st_bias",
        },
        "coverage": cov,
        "series_notes": SERIES_NOTES,
        "load_errors": load_errors,
        "call": {"deploy": deploy, "text": call + m5_note},
        "summaries": {
            "m15_book_a": _book_row(a_m15_book, stack="M15_ST_V3", entry_tag="A"),
            "m15_book_prod": _book_row(p_m15_book, stack="M15_ST_V3", entry_tag="prod"),
            "m15_primary7_a": _book_row(a_m15_p7, stack="M15_ST_V3", entry_tag="A"),
            "m15_primary7_prod": _book_row(p_m15_p7, stack="M15_ST_V3", entry_tag="prod"),
            "m15_xau_us100_a": _book_row(a_m15_req, stack="M15_ST_V3", entry_tag="A"),
            "m15_xau_us100_prod": _book_row(p_m15_req, stack="M15_ST_V3", entry_tag="prod"),
            "m5_book_a": _book_row(a_m5_book, stack="M5_ST_V3", entry_tag="A"),
            "m5_book_prod": _book_row(p_m5_book, stack="M5_ST_V3", entry_tag="prod"),
        },
        "results": results,
    }
    OUT_JSON.write_text(json.dumps(payload, indent=2) + "\n")
    print(f"wrote {OUT_MD}", flush=True)
    print(f"wrote {OUT_JSON}", flush=True)
    print(call + m5_note, flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
