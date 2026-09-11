#!/usr/bin/env python3
"""A–D bake-off: ST 10/2 vs 12/3 × pyramid OFF vs ON, half+BE+HA ~12m.

Research only. Does not invent Capital fills. Same window / loader as PR #139.
"""

from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from tools.ha_hunt_st_compare.ohlc import coverage_table, load_symbol, resample_bars
from tools.ha_hunt_st_compare.simulator import Book, Params, _ha_exit_locked, simulate

DOCS = ROOT / "docs"
OUT_MD = DOCS / "ha-hunt-st-pyramid-ab-12mo.md"
OUT_JSON = DOCS / "ha-hunt-st-pyramid-ab-12mo.json"

START = datetime(2025, 9, 11, tzinfo=timezone.utc)
END = datetime(2026, 9, 11, tzinfo=timezone.utc)

SYMBOLS = ["XAU", "US100", "BTCUSD"]

# Stack × A–D matrix. ST 10/2 is the pine / PR #139 lock; 12/3 is Adam's A/B.
STACKS = [
    {"stack": "M5+M45", "chart_minutes": 5, "st_tf_minutes": 45, "symbols": ["XAU", "US100", "BTCUSD"]},
    {"stack": "M15+H1", "chart_minutes": 15, "st_tf_minutes": 60, "symbols": ["US100"]},
]

CELLS = [
    ("A", "ST 10/2.0 flat", 10, 2.0, False),
    ("B", "ST 10/2.0 pyr", 10, 2.0, True),
    ("C", "ST 12/3.0 flat", 12, 3.0, False),
    ("D", "ST 12/3.0 pyr", 12, 3.0, True),
]


def _params(letter: str, atr_len: int, factor: float, pyr: bool, chart_minutes: int, st_tf_minutes: int) -> Params:
    return _ha_exit_locked(
        name=f"{letter}_st{atr_len}x{factor:g}_{'pyr' if pyr else 'flat'}_m{chart_minutes}_st{st_tf_minutes}",
        st_atr_len=atr_len,
        st_factor=factor,
        allow_pyramid=pyr,
        chart_minutes=chart_minutes,
        st_tf_minutes=st_tf_minutes,
    )


def _book_row(b: Book, stack: str, letter: str) -> dict:
    return {
        "stack": stack,
        "letter": letter,
        "symbol": b.symbol,
        "variant": b.variant,
        "n": b.n,
        "n_fills": b.n_fills,
        "n_pyramid": b.n_pyramid,
        "pyramid_pct": round(b.pyramid_pct, 1),
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


def _fmt_pf(pf: float) -> str:
    if pf == float("inf"):
        return "∞"
    return f"{pf:.2f}"


def _md_table(headers: list[str], rows: list[list[str]]) -> str:
    lines = ["| " + " | ".join(headers) + " |", "| " + " | ".join("---" for _ in headers) + " |"]
    for r in rows:
        lines.append("| " + " | ".join(r) + " |")
    return "\n".join(lines)


def _verdict(flat: Book, pyr: Book) -> str:
    d = pyr.sum_r - flat.sum_r
    if abs(d) < 1.0 and abs(pyr.max_dd_r - flat.max_dd_r) < 1.0:
        return "wash"
    if d >= 1.0 and pyr.max_dd_r <= flat.max_dd_r + 2.0:
        return "helps"
    if d <= -1.0:
        return "hurts"
    if pyr.max_dd_r > flat.max_dd_r + 3.0 and d < 3.0:
        return "hurts (fatter DD)"
    return "mixed"


def _st_verdict(a: Book, c: Book) -> str:
    d = c.sum_r - a.sum_r
    if abs(d) < 1.0:
        return "wash"
    return "12/3 wins" if d > 0 else "10/2 wins"


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

    results: list[dict] = []
    books: dict[tuple[str, str, str], Book] = {}
    for spec in STACKS:
        stack = spec["stack"]
        for sym in spec["symbols"]:
            if sym not in bars:
                continue
            src = bars[sym]
            chart = src if spec["chart_minutes"] == 5 else resample_bars(src, spec["chart_minutes"])
            print(f"stack {stack} {sym}  bars={len(chart.close)}", flush=True)
            for letter, _label, atr_len, factor, pyr in CELLS:
                p = _params(letter, atr_len, factor, pyr, spec["chart_minutes"], spec["st_tf_minutes"])
                book = simulate(sym, chart, p)
                books[(stack, sym, letter)] = book
                row = _book_row(book, stack, letter)
                results.append(row)
                print(
                    f"  {letter} {sym:7} n={book.n:4} fills={book.n_fills:4} pyr={book.n_pyramid:3} "
                    f"({book.pyramid_pct:4.1f}%)  WR={book.wr_pct:5.1f}%  sumR={book.sum_r:8.2f}  "
                    f"avgR={book.avg_r:6.3f}  DD={book.max_dd_r:6.2f}  PF={_fmt_pf(book.pf)}",
                    flush=True,
                )

    generated = datetime.now(timezone.utc).isoformat()
    cov = coverage_table(bars)

    def cell(stack: str, sym: str, letter: str) -> Book | None:
        return books.get((stack, sym, letter))

    def row_for(stack: str, sym: str, letter: str) -> list[str]:
        b = cell(stack, sym, letter)
        if b is None:
            return [letter, "—", "—", "—", "—", "—", "—", "—", "—", "—"]
        return [
            letter,
            str(b.n),
            f"{b.wr_pct:.1f}",
            f"{b.sum_r:+.1f}",
            f"{b.avg_r:+.3f}",
            _fmt_pf(b.pf),
            f"{b.max_dd_r:.1f}",
            str(b.n_fills),
            f"{b.n_pyramid} ({b.pyramid_pct:.0f}%)",
            f"{100.0 * b.n_tp1 / b.n:.1f}" if b.n else "—",
        ]

    lines: list[str] = [
        "# HA-Hunt ST — pyramid A/B × Supertrend 10/2 vs 12/3 (~12 months)",
        "",
        f"Generated `{generated}`. Simulator: `tools/ha_hunt_st_compare` matching PR #139 half+BE+HA "
        "and PR #142 same-direction pyramid. **Do not merge as live logic.**",
        "",
        "## Question",
        "",
        "Adam does not want PR #142 merged until this is measured: does same-direction pyramiding "
        "(add while open, `capReg=2`) improve the locked half+BE+HA books vs the current "
        "`pos == 0` gate? Second axis: Supertrend **ATR 10 / factor 2.0** (pine default / prior "
        "bakeoffs) vs **ATR 12 / factor 3.0**.",
        "",
        "## Locked rules (identical across A–D)",
        "",
        "- Slow RMA **144**, fast **33**, M45 structure gate **ON**, `capReg` **2**, both sides.",
        "- Trigger = first chart-TF close beyond the fast band (`bandCrossStrict` off).",
        "- Bias + initial SL = closed Supertrend of the stack TF (M45 or H1).",
        "- Management: 50% at 1:2 → stop to **BE (avg entry)** → runner exit on confirmed "
        "chart-TF HA body flip against (or BE).",
        "- Conservative same-bar: stop before TP1.",
        "- **A / C** = no pyramid (`pos == 0`). Sequential fills after a close still allowed up to cap 2.",
        "- **B / D** = PR #142 pyramid: same-direction add while open; opposite ignored; "
        "aggregated avg entry; SL stays at first protective stop (or BE after TP1); "
        "size-weighted R vs first-fill 1R so a 2-unit stop ≈ −2R.",
        "",
        "## Data",
        "",
        "Capital DEMO mid caches / API keys were **not** used unless present. Prices are real "
        "HistData M1 resampled to M5 (XAU, US100=NSXUSD) and Coinbase Exchange 5m for BTCUSD. "
        "**Not Capital mid — do not treat as live fills.** Same window as PR #139.",
        "",
    ]

    cov_rows = []
    for c in cov:
        cov_rows.append(
            [
                c["symbol"],
                c["source"],
                str(c["first"])[:19],
                str(c["last"])[:19],
                str(c["n_m5"]),
                str(c["coverage_days"]),
                (c["note"] or "")[:80],
            ]
        )
    lines += [
        _md_table(["symbol", "source", "first", "last", "n_m5", "days", "note"], cov_rows),
        "",
        "US500 / US30 comparison: **not run** (deferred).",
        "",
        "## Side-by-side",
        "",
        "n = completed books (open→flat). `2nd add` = books that actually received a pyramid fill. "
        "WR% = share of books with size-weighted R > 0. sumR / DD are in first-fill R units "
        "(2-unit books count ~2×).",
        "",
    ]

    headers = ["mode", "n", "WR%", "sumR", "avgR", "PF", "maxDD(R)", "fills", "2nd add", "TP1%"]

    required = [
        ("XAU", "M5+M45", "Primary winner — M5 trigger, M45 ST"),
        ("US100", "M5+M45", "NQ / US100 M5 + M45 ST"),
        ("US100", "M15+H1", "NQ / US100 M15 + H1 ST (prior ~41.8% / +48R on 10/2 flat)"),
    ]
    optional = [("BTCUSD", "M5+M45", "Optional — Coinbase 5m, same method")]

    calls: list[str] = []
    for sym, stack, title in required + optional:
        if (stack, sym, "A") not in books:
            continue
        lines += [f"### {sym} — {stack}", "", f"{title}.", "", _md_table(headers, [row_for(stack, sym, L) for L, *_ in CELLS]), ""]
        a, b, c, d = (cell(stack, sym, L) for L in "ABCD")
        assert a and b and c and d
        pyr_10 = _verdict(a, b)
        pyr_12 = _verdict(c, d)
        st_flat = _st_verdict(a, c)
        best = max((a, b, c, d), key=lambda x: (x.sum_r, x.pf if x.pf != float("inf") else 99, -x.max_dd_r))
        letter_of = {id(cell(stack, sym, L)): L for L in "ABCD"}
        best_L = letter_of[id(best)]
        lines += [
            f"- Pyramid @ 10/2: **{pyr_10}** (B − A = {b.sum_r - a.sum_r:+.1f}R; 2nd add on {b.n_pyramid}/{b.n} books).",
            f"- Pyramid @ 12/3: **{pyr_12}** (D − C = {d.sum_r - c.sum_r:+.1f}R; 2nd add on {d.n_pyramid}/{d.n} books).",
            f"- ST params, flat only (C vs A): **{st_flat}** ({c.sum_r - a.sum_r:+.1f}R).",
            f"- Best cell: **{best_L}** n={best.n} WR={best.wr_pct:.1f}% sumR={best.sum_r:+.1f} PF={_fmt_pf(best.pf)} DD={best.max_dd_r:.1f}R.",
            "",
        ]
        if (sym, stack) in {(s, t) for s, t, _ in required}:
            calls.append(
                f"| {sym} | {stack} | {pyr_10} | {pyr_12} | {st_flat} | {best_L} {best.sum_r:+.1f}R / {best.wr_pct:.1f}% |"
            )

    # Comparison table
    cmp_headers = ["symbol", "stack", "A 10/2 flat", "B 10/2 pyr", "C 12/3 flat", "D 12/3 pyr", "pyr 10/2", "pyr 12/3", "ST winner"]
    cmp_rows = []
    for sym, stack, _title in required + optional:
        if (stack, sym, "A") not in books:
            continue
        a, b, c, d = (cell(stack, sym, L) for L in "ABCD")
        assert a and b and c and d

        def brief(x: Book) -> str:
            return f"{x.wr_pct:.1f}% / {x.sum_r:+.1f}R / DD {x.max_dd_r:.1f}"

        cmp_rows.append(
            [
                sym,
                stack,
                brief(a),
                brief(b),
                brief(c),
                brief(d),
                _verdict(a, b),
                _verdict(c, d),
                _st_verdict(a, c),
            ]
        )

    lines += [
        "## One table — every required cell",
        "",
        _md_table(cmp_headers, cmp_rows),
        "",
        "## Call",
        "",
        "| symbol | stack | pyramid @ 10/2 | pyramid @ 12/3 | 10/2 vs 12/3 (flat) | best cell |",
        "| --- | --- | --- | --- | --- | --- |",
        *calls,
        "",
    ]

    # Overall recommendation
    xau_a = cell("M5+M45", "XAU", "A")
    xau_best = None
    if xau_a:
        cands = [cell("M5+M45", "XAU", L) for L in "ABCD"]
        xau_best = max([x for x in cands if x], key=lambda x: x.sum_r)

    nq_m15_a = cell("M15+H1", "US100", "A")
    nq_m5_a = cell("M5+M45", "US100", "A")

    rec_bits = []
    if xau_a and xau_best:
        rec_bits.append(
            f"XAU M5+M45 stays the quality name (A flat 10/2 = {xau_a.sum_r:+.1f}R / {xau_a.wr_pct:.1f}% WR). "
            f"Best XAU cell is {xau_best.variant.split('_')[0]} at {xau_best.sum_r:+.1f}R."
        )
    pyr_help = 0
    pyr_hurt = 0
    for sym, stack, _ in required:
        a, b = cell(stack, sym, "A"), cell(stack, sym, "B")
        c, d = cell(stack, sym, "C"), cell(stack, sym, "D")
        if a and b:
            v = _verdict(a, b)
            if v.startswith("helps"):
                pyr_help += 1
            elif v.startswith("hurts"):
                pyr_hurt += 1
        if c and d:
            v = _verdict(c, d)
            if v.startswith("helps"):
                pyr_help += 1
            elif v.startswith("hurts"):
                pyr_hurt += 1
    if pyr_hurt > pyr_help:
        rec_bits.append(
            f"Pyramid **does not earn a merge**: it hurts more cells than it helps "
            f"({pyr_hurt} hurt / {pyr_help} help across the three required stacks × two ST settings)."
        )
    elif pyr_help > pyr_hurt:
        rec_bits.append(
            f"Pyramid is a **net help** on the required book ({pyr_help} help / {pyr_hurt} hurt) "
            "but check DD before merging #142."
        )
    else:
        rec_bits.append(
            "Pyramid is a **wash** on the required book — not enough edge to merge #142."
        )

    st10 = 0
    st12 = 0
    for sym, stack, _ in required:
        a, c = cell(stack, sym, "A"), cell(stack, sym, "C")
        if a and c:
            if c.sum_r - a.sum_r > 1:
                st12 += 1
            elif a.sum_r - c.sum_r > 1:
                st10 += 1
    if st10 > st12:
        rec_bits.append("Keep Supertrend **10 / 2.0** (pine default). 12/3 does not beat it on the required stacks.")
    elif st12 > st10:
        rec_bits.append("Supertrend **12 / 3.0** beats 10/2 on more required stacks — consider switching the lock.")
    else:
        rec_bits.append("Supertrend 10/2 vs 12/3 is a wash on the required stacks; keep the pine default **10 / 2.0**.")

    if nq_m15_a:
        rec_bits.append(
            f"US100 M15+H1 A (10/2 flat) printed {nq_m15_a.wr_pct:.1f}% / {nq_m15_a.sum_r:+.1f}R "
            "(prior published cell was ~41.8% / +48R on the same method)."
        )
    if nq_m5_a:
        rec_bits.append(
            f"US100 M5+M45 A (10/2 flat) printed {nq_m5_a.wr_pct:.1f}% / {nq_m5_a.sum_r:+.1f}R "
            "(prior published cell was ~35.0% / +13R)."
        )

    lines += [
        " ".join(rec_bits),
        "",
        "**Do not merge PR #142** from this note. Re-run after any pine change.",
        "",
        "## How to rerun",
        "",
        "```bash",
        "python3 -m tools.ha_hunt_st_compare.test_simulator",
        "python3 -m tools.ha_hunt_st_compare.run_pyramid_ab",
        "```",
        "",
        "With Capital DEMO env (`CAPITAL_API_KEY`, `CAPITAL_EMAIL`, `CAPITAL_API_PASSWORD`) the loader prefers Capital mid M5. "
        "Caches live under `tools/ha_hunt_st_compare/cache/` (gitignored).",
        "",
    ]

    OUT_MD.write_text("\n".join(lines) if lines[-1] == "" else "\n".join(lines) + "\n")
    payload = {
        "generated": generated,
        "window": {"start": START.isoformat(), "end": END.isoformat()},
        "rules": {
            "slow_len": 144,
            "fast_len": 33,
            "gate": True,
            "cap_reg": 2,
            "management": "half+BE+HA",
            "st_default": "ATR 10 / factor 2.0",
            "st_alt": "ATR 12 / factor 3.0",
            "pyramid_on": "PR #142 same-direction add while open",
            "pyramid_off": "pos == 0 (main / pre-#142)",
            "r_note": "size-weighted vs first-fill 1R for multi-unit books",
        },
        "coverage": cov,
        "load_errors": load_errors,
        "cells": results,
        "deferred": ["US500", "US30"],
    }
    OUT_JSON.write_text(json.dumps(payload, indent=2) + "\n")
    print(f"wrote {OUT_MD}")
    print(f"wrote {OUT_JSON}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
