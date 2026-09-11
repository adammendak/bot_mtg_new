#!/usr/bin/env python3
"""ST param grid + US100/US500/US30 compare. Half+BE+HA, pyramid OFF.

Research only. Same window / loader as PR #139 / #143. Does not invent prices.
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

from tools.ha_hunt_st_compare.ohlc import (
    SERIES_NOTES,
    coverage_table,
    load_symbol,
    resample_bars,
    series_note,
)
from tools.ha_hunt_st_compare.simulator import Book, Params, _ha_exit_locked, simulate

DOCS = ROOT / "docs"
OUT_MD = DOCS / "ha-hunt-st-params-indices-12mo.md"
OUT_JSON = DOCS / "ha-hunt-st-params-indices-12mo.json"

START = datetime(2025, 9, 11, tzinfo=timezone.utc)
END = datetime(2026, 9, 11, tzinfo=timezone.utc)

# Classic TV Supertrend ATR length × factor. 10/2 is the pine / #139/#143 lock.
# 12/3 already measured on #143. Extra cheap cells: 10/2.5 and 8/2.
ST_GRID: list[tuple[int, float]] = [
    (10, 2.0),
    (12, 3.0),
    (10, 3.0),
    (14, 2.0),
    (14, 3.0),
    (7, 2.0),
    (7, 3.0),
    (12, 2.0),
    (10, 2.5),
    (8, 2.0),
]

# Deduped Part A cells: XAU M5+M45, US100 M15+H1 (required), US100 M5+M45 (optional).
PART_A_CELLS = [
    ("XAU", "M5+M45", 5, 45, True),
    ("US100", "M15+H1", 15, 60, True),
    ("US100", "M5+M45", 5, 45, False),
]

PART_B_SYMBOLS = ["US100", "US500", "US30"]
PART_B_STACKS = [
    ("M5+M45", 5, 45),
    ("M15+H1", 15, 60),
]

REQUIRED_KEYS = [("XAU", "M5+M45"), ("US100", "M15+H1")]


def locked_flat(atr_len: int, factor: float, chart_minutes: int, st_tf_minutes: int) -> Params:
    return _ha_exit_locked(
        name=f"st{atr_len}x{factor:g}_flat_m{chart_minutes}_st{st_tf_minutes}",
        st_atr_len=atr_len,
        st_factor=factor,
        allow_pyramid=False,
        chart_minutes=chart_minutes,
        st_tf_minutes=st_tf_minutes,
    )


def _fmt_pf(pf: float) -> str:
    if pf == float("inf"):
        return "∞"
    return f"{pf:.2f}"


def _md_table(headers: list[str], rows: list[list[str]]) -> str:
    align = []
    for h in headers:
        align.append("---:" if h.lower() not in {"stack", "symbol", "st", "mode", "source", "note", "cell", "call"} else "---")
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
        "n_fills": b.n_fills,
        "n_pyramid": b.n_pyramid,
        "exits": b.exits,
    }
    row.update(extra)
    return row


def _brief(b: Book) -> str:
    return f"{b.wr_pct:.1f}% / {b.sum_r:+.1f}R / DD {b.max_dd_r:.1f}"


def pick_best_st(
    books: dict[tuple[str, str, int, float], Book],
    grid: list[tuple[int, float]] = ST_GRID,
    required: list[tuple[str, str]] = REQUIRED_KEYS,
    baseline: tuple[int, float] = (10, 2.0),
) -> tuple[int, float, str]:
    """Keep 10/2 unless another (len, factor) beats it on **both** required stacks.

    Beat = higher sumR on every required cell. Ties / mixed winners stay on 10/2.
    If several beat both, pick the highest combined required-stack sumR.
    """
    base_sum = []
    for key in required:
        b = books.get((key[0], key[1], baseline[0], baseline[1]))
        if b is None:
            return (*baseline, "baseline missing — keep 10/2")
        base_sum.append(b.sum_r)

    winners: list[tuple[float, int, float]] = []
    notes = []
    for atr_len, factor in grid:
        if (atr_len, factor) == baseline:
            continue
        ok = True
        comb = 0.0
        bits = []
        for i, key in enumerate(required):
            b = books.get((key[0], key[1], atr_len, factor))
            if b is None:
                ok = False
                break
            d = b.sum_r - base_sum[i]
            bits.append(f"{key[0]} {key[1]} {d:+.1f}R")
            if d <= 0:
                ok = False
            comb += b.sum_r
        notes.append(f"{atr_len}/{factor:g}: {', '.join(bits) if bits else 'incomplete'} → {'beats' if ok else 'no'}")
        if ok:
            winners.append((comb, atr_len, factor))

    if not winners:
        return (*baseline, "no grid cell beat 10/2 on both required stacks (XAU M5+M45 and US100 M15+H1) by sumR")
    winners.sort(reverse=True)
    _comb, atr_len, factor = winners[0]
    return atr_len, factor, f"{atr_len}/{factor:g} beat 10/2 on both required stacks; highest combined sumR among beaters"


def closeness_score(book: Book, nq: Book) -> float:
    pf_b = 0.0 if book.pf == float("inf") else book.pf
    pf_n = 0.0 if nq.pf == float("inf") else nq.pf
    return (
        abs(book.wr_pct - nq.wr_pct)
        + abs(book.sum_r - nq.sum_r) / 10.0
        + abs(pf_b - pf_n)
        + abs(book.max_dd_r - nq.max_dd_r) / 10.0
    )


def _simulate_cell(sym: str, chart, atr_len: int, factor: float, chart_minutes: int, st_tf_minutes: int) -> Book:
    p = locked_flat(atr_len, factor, chart_minutes, st_tf_minutes)
    return simulate(sym, chart, p)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="ST grid + indices research (pyramid OFF)")
    ap.add_argument("--part", choices=["all", "a", "b"], default="all")
    args = ap.parse_args(argv)

    DOCS.mkdir(parents=True, exist_ok=True)
    print(f"window {START.isoformat()} → {END.isoformat()}", flush=True)
    print("pyramid OFF · half+BE+HA · slow 144 · fast 33 · M45 gate ON · capReg 2 (pos==0)", flush=True)

    need = ["XAU", "US100"]
    if args.part in ("all", "b"):
        need = ["XAU", "US100", "US500", "US30"]

    bars = {}
    load_errors = {}
    for sym in need:
        print(f"load {sym}… {series_note(sym)}", flush=True)
        try:
            bars[sym] = load_symbol(sym, START, END, prefer_capital=True)
            m = bars[sym].meta
            print(f"  {m.source}  n_m5={m.n_m5}  n_m1={m.n_m1}  {m.first} → {m.last}", flush=True)
        except Exception as e:
            load_errors[sym] = str(e)
            print(f"  FAIL {e}", flush=True)

    if "XAU" not in bars or "US100" not in bars:
        print("required symbols XAU/US100 failed", file=sys.stderr)
        return 1

    results: list[dict] = []
    part_a: dict[tuple[str, str, int, float], Book] = {}
    charts: dict[tuple[str, int], object] = {}

    def chart_for(sym: str, minutes: int):
        key = (sym, minutes)
        if key not in charts:
            src = bars[sym]
            charts[key] = src if minutes == 5 else resample_bars(src, minutes)
        return charts[key]

    if args.part in ("all", "a"):
        for sym, stack, chart_min, st_min, _req in PART_A_CELLS:
            if sym not in bars:
                continue
            chart = chart_for(sym, chart_min)
            print(f"Part A  {sym} {stack}  bars={len(chart.close)}", flush=True)
            for atr_len, factor in ST_GRID:
                book = _simulate_cell(sym, chart, atr_len, factor, chart_min, st_min)
                part_a[(sym, stack, atr_len, factor)] = book
                results.append(
                    _book_row(book, part="A", stack=stack, st_atr_len=atr_len, st_factor=factor)
                )
                print(
                    f"  ST {atr_len}/{factor:g}  n={book.n:4}  WR={book.wr_pct:5.1f}%  "
                    f"sumR={book.sum_r:8.2f}  PF={_fmt_pf(book.pf)}  DD={book.max_dd_r:6.2f}",
                    flush=True,
                )

    best_len, best_fac, best_why = pick_best_st(part_a) if part_a else (10, 2.0, "Part A skipped — keep 10/2")
    print(f"Part A call: ST {best_len}/{best_fac:g}  ({best_why})", flush=True)

    part_b: dict[tuple[str, str], Book] = {}
    if args.part in ("all", "b"):
        if args.part == "b" and not part_a:
            best_len, best_fac = 10, 2.0
            best_why = "Part B only — ST 10/2"
        for stack, chart_min, st_min in PART_B_STACKS:
            for sym in PART_B_SYMBOLS:
                if sym not in bars:
                    print(f"Part B skip {sym} {stack} (no data)", flush=True)
                    continue
                chart = chart_for(sym, chart_min)
                print(f"Part B  {sym} {stack}  ST {best_len}/{best_fac:g}  bars={len(chart.close)}", flush=True)
                book = _simulate_cell(sym, chart, best_len, best_fac, chart_min, st_min)
                part_b[(sym, stack)] = book
                results.append(
                    _book_row(
                        book,
                        part="B",
                        stack=stack,
                        st_atr_len=best_len,
                        st_factor=best_fac,
                    )
                )
                print(
                    f"  n={book.n:4}  WR={book.wr_pct:5.1f}%  sumR={book.sum_r:8.2f}  "
                    f"PF={_fmt_pf(book.pf)}  DD={book.max_dd_r:6.2f}",
                    flush=True,
                )

    generated = datetime.now(timezone.utc).isoformat()
    cov = coverage_table(bars)

    def a_cell(sym: str, stack: str, atr_len: int, factor: float) -> Book | None:
        return part_a.get((sym, stack, atr_len, factor))

    lines: list[str] = [
        "# HA-Hunt ST — Supertrend params + US100/US500/US30 (~12 months)",
        "",
        f"Generated `{generated}`. Simulator: `tools/ha_hunt_st_compare` matching PR #139 / #143 "
        "half+BE+HA. **Pyramid OFF** (`pos == 0`). **Do not merge as live logic.** Does not merge PR #142.",
        "",
        "## Question",
        "",
        "Two research axes on the locked stack:",
        "",
        "1. **Part A** — does any classic TradingView Supertrend `(ATR length × factor)` beat the "
        "pine default **10 / 2.0** on total R / PF / DD / WR?",
        "2. **Part B** — on the winning ST (or 10/2 if nothing beats it), how do **US500** and "
        "**US30** compare to **US100 / NQ**?",
        "",
        "Hypothesis (pre-registered): 10/2 remains best; US500 tracks US100 closer than US30.",
        "",
        "## Locked rules (identical across every cell)",
        "",
        "- Slow RMA **144**, fast **33**, M45 structure gate **ON**, `capReg` **2**, both sides.",
        "- **Flat only:** `canEnter = fills < capReg and pos == 0`. Sequential fills after a close "
        "still allowed up to cap 2. **No pyramid.** PR #142 stays closed.",
        "- Trigger = first chart-TF close beyond the fast band (`bandCrossStrict` off).",
        "- Bias + initial SL = closed Supertrend of the stack TF (M45 or H1).",
        "- Management: 50% at 1:2 → stop to **BE (avg entry)** → runner exit on confirmed "
        "chart-TF HA body flip against (or BE).",
        "- Conservative same-bar: stop before TP1.",
        "",
        "## Data",
        "",
        "Capital DEMO mid caches / API keys were **not** used unless present. Prices are real "
        "HistData M1 resampled to M5 (XAU, US100=NSXUSD, US500=SPXUSD) and Dukascopy M1 "
        "resampled to M5 for US30 (USA30.IDX/USD — HistData has no DJIA pair). "
        "**Not Capital mid — do not treat as live fills.** Same window as PR #139 / #143.",
        "",
        "Repo mapping (Capital epics in `application.properties`): US100→`US100`, US500→`US500`, "
        "US30→`US30` (risk-watch only; SDD comment is \"not US30\").",
        "",
    ]

    cov_rows = []
    for r in cov:
        cov_rows.append(
            [
                r["symbol"],
                r["source"],
                r["first"][:19] if r["first"] else "—",
                r["last"][:19] if r["last"] else "—",
                str(r["n_m5"]),
                str(r["coverage_days"]),
                (r["note"] or SERIES_NOTES.get(r["symbol"], ""))[:90],
            ]
        )
    lines += [
        _md_table(["symbol", "source", "first", "last", "n_m5", "days", "note"], cov_rows),
        "",
    ]
    if load_errors:
        lines += ["Load failures:", ""]
        for sym, err in load_errors.items():
            lines.append(f"- `{sym}`: {err}")
        lines.append("")

    lines += [
        "### Series used",
        "",
        "- **US100 / NQ:** HistData `NSXUSD` (Nasdaq 100 cash). Same series as PR #139/#143.",
        "- **US500 / ES:** HistData `SPXUSD` (S&P 500 cash). Same vendor + M1→M5 method as US100. "
        "Not ES futures.",
        "- **US30 / YM:** HistData does **not** list DJIA (UDXUSD is the Dollar Index). "
        "Dukascopy `USA30.IDX/USD` cash DJIA, M1 bid → M5. UTC-native stamps (HistData is Eastern→UTC). "
        "Prints at DJIA cash (~52–53k in this window), not a YM point-value contract.",
        "",
        "## Part A — Supertrend ATR × factor",
        "",
        "Grid vs baseline **10 / 2.0**. Already measured on #143: **12 / 3.0** (worse on the "
        "star cells). Also: 10/3, 14/2, 14/3, 7/2, 7/3, 12/2, plus cheap extras 10/2.5 and 8/2.",
        "",
        "A cell **beats 10/2** only if its **sumR is higher on both required stacks** "
        "(XAU M5+M45 and US100 M15+H1). Mixed / one-stack wins stay on 10/2.",
        "",
    ]

    def grid_table(sym: str, stack: str) -> None:
        nonlocal lines
        rows = []
        base = a_cell(sym, stack, 10, 2.0)
        for atr_len, factor in ST_GRID:
            b = a_cell(sym, stack, atr_len, factor)
            if b is None:
                continue
            d_r = b.sum_r - base.sum_r if base else 0.0
            d_pf = (0 if b.pf == float("inf") else b.pf) - (0 if not base or base.pf == float("inf") else base.pf)
            d_dd = b.max_dd_r - base.max_dd_r if base else 0.0
            d_wr = b.wr_pct - base.wr_pct if base else 0.0
            tag = "baseline" if (atr_len, factor) == (10, 2.0) else ("beats on this stack" if d_r > 0 else "worse/tie R")
            rows.append(
                [
                    f"{atr_len}/{factor:g}",
                    str(b.n),
                    f"{b.wr_pct:.1f}",
                    f"{b.sum_r:+.1f}",
                    f"{b.avg_r:+.3f}",
                    _fmt_pf(b.pf),
                    f"{b.max_dd_r:.1f}",
                    f"{d_r:+.1f}",
                    f"{d_pf:+.2f}",
                    f"{d_dd:+.1f}",
                    f"{d_wr:+.1f}",
                    tag,
                ]
            )
        lines += [
            f"### {sym} — {stack}",
            "",
            _md_table(
                ["ST", "n", "WR%", "sumR", "avgR", "PF", "maxDD(R)", "ΔR vs 10/2", "ΔPF", "ΔDD", "ΔWR", "vs 10/2"],
                rows,
            ),
            "",
        ]

    if part_a:
        grid_table("XAU", "M5+M45")
        grid_table("US100", "M15+H1")
        grid_table("US100", "M5+M45")

        # Beat summary
        beat_rows = []
        for atr_len, factor in ST_GRID:
            if (atr_len, factor) == (10, 2.0):
                continue
            flags = []
            both = True
            for sym, stack in REQUIRED_KEYS:
                b = a_cell(sym, stack, atr_len, factor)
                base = a_cell(sym, stack, 10, 2.0)
                if b is None or base is None:
                    flags.append("—")
                    both = False
                    continue
                win = b.sum_r > base.sum_r
                flags.append(f"{b.sum_r - base.sum_r:+.1f}R" + (" ✓" if win else " ✗"))
                if not win:
                    both = False
            beat_rows.append([f"{atr_len}/{factor:g}", *flags, "YES" if both else "no"])
        lines += [
            "### Who beats 10/2 on **both** required stacks?",
            "",
            _md_table(["ST", "XAU M5+M45 ΔR", "US100 M15+H1 ΔR", "beats both?"], beat_rows),
            "",
            f"**Part A call:** Supertrend **{best_len} / {best_fac:g}**. {best_why}.",
            "",
        ]

    lines += [
        "## Part B — US100 vs US500 vs US30",
        "",
        f"Same locked stack, pyramid OFF, ST **{best_len} / {best_fac:g}** ({best_why}).",
        "",
    ]

    if part_b:
        for stack, _c, _s in PART_B_STACKS:
            nq = part_b.get(("US100", stack))
            rows = []
            for sym in PART_B_SYMBOLS:
                b = part_b.get((sym, stack))
                if b is None:
                    rows.append([sym, "—", "—", "—", "—", "—", "—", "missing"])
                    continue
                score = closeness_score(b, nq) if nq and sym != "US100" else 0.0
                note = "NQ baseline" if sym == "US100" else f"distance {score:.2f} (lower=closer to NQ)"
                rows.append(
                    [
                        sym,
                        str(b.n),
                        f"{b.wr_pct:.1f}",
                        f"{b.sum_r:+.1f}",
                        _fmt_pf(b.pf),
                        f"{b.max_dd_r:.1f}",
                        f"{score:.2f}" if sym != "US100" else "—",
                        note,
                    ]
                )
            lines += [
                f"### {stack}",
                "",
                _md_table(["symbol", "n", "WR%", "sumR", "PF", "maxDD(R)", "NQ distance", "note"], rows),
                "",
            ]

        # Cross-stack summary + closeness call
        lines += [
            "### One table — every Part B cell",
            "",
        ]
        sum_rows = []
        for stack, _c, _s in PART_B_STACKS:
            nq = part_b.get(("US100", stack))
            for sym in PART_B_SYMBOLS:
                b = part_b.get((sym, stack))
                if b is None:
                    continue
                sum_rows.append(
                    [
                        sym,
                        stack,
                        str(b.n),
                        f"{b.wr_pct:.1f}",
                        f"{b.sum_r:+.1f}",
                        _fmt_pf(b.pf),
                        f"{b.max_dd_r:.1f}",
                        _brief(b) if nq is None or sym == "US100" else f"{_brief(b)}  (ΔR {b.sum_r - nq.sum_r:+.1f})",
                    ]
                )
        lines += [
            _md_table(["symbol", "stack", "n", "WR%", "sumR", "PF", "maxDD", "vs NQ"], sum_rows),
            "",
        ]

        closer_bits = []
        for stack, _c, _s in PART_B_STACKS:
            nq = part_b.get(("US100", stack))
            a = part_b.get(("US500", stack))
            z = part_b.get(("US30", stack))
            if nq and a and z:
                sa, sz = closeness_score(a, nq), closeness_score(z, nq)
                closer = "US500" if sa < sz else ("US30" if sz < sa else "tie")
                closer_bits.append((stack, closer, sa, sz, a, z, nq))

        worth = []
        for (sym, stack), b in part_b.items():
            if sym == "US100":
                continue
            flag = "worth a look" if (b.sum_r > 0 and b.pf > 1.0) else "not worth trading on this stack"
            worth.append(f"- **{sym} {stack}:** {flag} ({_brief(b)}, PF {_fmt_pf(b.pf)}, n={b.n}).")

        lines += ["### Closeness / tradeability", ""]
        for stack, closer, sa, sz, a, z, nq in closer_bits:
            lines.append(
                f"- **{stack}:** {closer} is closer to US100 "
                f"(US500 distance {sa:.2f}, US30 distance {sz:.2f}; "
                f"US100 {_brief(nq)})."
            )
        lines.append("")
        lines.extend(worth)
        lines.append("")

        us500_closer = all(c[1] == "US500" for c in closer_bits) if closer_bits else False
        hyp_st = (best_len, best_fac) == (10, 2.0)
        lines += [
            "## Call",
            "",
            f"- **Supertrend:** keep / use **{best_len} / {best_fac:g}**. {best_why}.",
            f"- **Hypothesis 10/2 remains best:** {'confirmed' if hyp_st else 'rejected — a grid cell beat 10/2 on both required stacks'}.",
            f"- **Hypothesis US500 tracks US100 closer than US30:** "
            f"{'confirmed on both stacks' if us500_closer else 'see closeness bullets — not uniformly true' if closer_bits else 'incomplete'}.",
            "- **Pyramid:** still OFF. Do not merge PR #142.",
            "- **Practical lock:** keep trading the #143 A-cells (XAU M5+M45 and US100 M15+H1) "
            f"at ST {best_len}/{best_fac:g} flat. Add US500 only if Part B sumR/PF hold; "
            "treat US30 as a different animal (vendor + beta).",
            "",
            "**Do not merge this note as live pine.** Re-run after any pine change.",
            "",
        ]
    else:
        lines += ["Part B not run (missing symbols or `--part a`).", ""]

    lines += [
        "## How to rerun",
        "",
        "```bash",
        "python3 -m pip install pandas numpy histdata-fetcher dukascopy-python",
        "python3 -m tools.ha_hunt_st_compare.test_simulator",
        "python3 -m tools.ha_hunt_st_compare.run_st_indices",
        "```",
        "",
        "With Capital DEMO env (`CAPITAL_API_KEY`, `CAPITAL_EMAIL`, `CAPITAL_API_PASSWORD`) "
        "the loader prefers Capital mid M5. Caches live under `tools/ha_hunt_st_compare/cache/` "
        "(gitignored). US30 always needs Dukascopy unless Capital is set (HistData has no DJIA).",
        "",
    ]

    OUT_MD.write_text("\n".join(lines) + "\n")
    payload = {
        "generated": generated,
        "window": {"start": START.isoformat(), "end": END.isoformat()},
        "locked": {
            "slow_rma": 144,
            "fast": 33,
            "m45_gate": True,
            "cap_reg": 2,
            "pyramid": False,
            "exit": "half_tp1_be_ha_flip",
        },
        "st_grid": [{"atr_len": a, "factor": f} for a, f in ST_GRID],
        "best_st": {"atr_len": best_len, "factor": best_fac, "why": best_why},
        "coverage": cov,
        "series_notes": SERIES_NOTES,
        "load_errors": load_errors,
        "results": results,
    }
    OUT_JSON.write_text(json.dumps(payload, indent=2) + "\n")
    print(f"wrote {OUT_MD}", flush=True)
    print(f"wrote {OUT_JSON}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
