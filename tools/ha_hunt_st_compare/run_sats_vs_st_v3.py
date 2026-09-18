#!/usr/bin/env python3
"""12-month SATS Default vs ST_V3 prod-like bakeoff + cheap TQI / char-flip A/B.

Research only. Does not change prod Java. Reuses ``tools.ha_hunt_st_compare``
OHLC (same sources as PR #151/#152): HistData M1→M5, Dukascopy US30, Coinbase BTC.

Window: entries 2025-09-13 → 2026-09-13. SATS runs on the chart TF only
(M15 and M5). ST_V3 stays stacked (M15+H1, M5+M45).
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

from tools.ha_hunt_st_compare.ohlc import (  # noqa: E402
    PRIMARY_SYMBOLS,
    SERIES_NOTES,
    coverage_table,
    load_symbol,
    resample_bars,
    series_note,
)
from tools.ha_hunt_st_compare.sats import SIMPLIFICATIONS, default_sats, simulate_sats  # noqa: E402
from tools.ha_hunt_st_compare.simulator import Book, _metrics, simulate, st_v3_prod_like  # noqa: E402

DOCS = ROOT / "docs"
OUT_MD = DOCS / "sats-vs-st-v3-12m.md"
OUT_JSON = DOCS / "sats-vs-st-v3-12m.json"

TRADE_END = datetime(2026, 9, 13, tzinfo=timezone.utc)
TRADE_START = datetime(2025, 9, 13, tzinfo=timezone.utc)
WARMUP_DAYS = 120
FETCH_START = TRADE_START - timedelta(days=WARMUP_DAYS)

PRIMARY7 = ("XAU", "BTC", "US100", "US500", "US30", "GER40", "EURUSD")
TRADE_START_ISO = TRADE_START.strftime("%Y-%m-%dT%H:%M:%S")
TRADE_END_ISO = TRADE_END.strftime("%Y-%m-%dT%H:%M:%S")

# (key, stack_label, kind)
# kind: sats_m15 / sats_m5 / stv3
CELLS = [
    ("SATS_M15", "SATS M15 chart-TF", "sats_m15"),
    ("SATS_M5", "SATS M5 chart-TF", "sats_m5"),
    ("STV3_M15H1", "ST_V3 prod M15+H1", "stv3_m15"),
    ("STV3_M5M45", "ST_V3 prod M5+M45", "stv3_m5"),
    ("STV3_M15H1_TQI", "ST_V3 M15+H1 TQI≥0.5 gate", "stv3_m15_tqi"),
    ("STV3_M5M45_TQI", "ST_V3 M5+M45 TQI≥0.5 gate", "stv3_m5_tqi"),
    ("STV3_M15H1_CFONLY", "ST_V3 M15+H1 char-flip instead of band runner", "stv3_m15_cfonly"),
    ("STV3_M5M45_CFONLY", "ST_V3 M5+M45 char-flip instead of band runner", "stv3_m5_cfonly"),
    ("STV3_M15H1_CFPLUS", "ST_V3 M15+H1 char-flip + band runner", "stv3_m15_cfplus"),
    ("STV3_M5M45_CFPLUS", "ST_V3 M5+M45 char-flip + band runner", "stv3_m5_cfplus"),
]


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
            "mode",
            "source",
            "note",
            "cell",
            "call",
            "variant",
            "winner",
            "idea",
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


def _row(sym: str, stack: str, b: Book, note: str = "") -> list[str]:
    return [
        sym,
        stack,
        str(b.n),
        f"{b.wr_pct:.1f}",
        f"{b.sum_r:+.2f}",
        _fmt_pf(b.pf),
        f"{b.max_dd_r:.2f}",
        f"{b.avg_r:.3f}",
        note,
    ]


def _params_for(kind: str):
    ts, te = TRADE_START_ISO, TRADE_END_ISO
    if kind == "sats_m15":
        return default_sats(15, ts, te)
    if kind == "sats_m5":
        return default_sats(5, ts, te)
    if kind == "stv3_m15":
        return st_v3_prod_like(15, 60, ts, te)
    if kind == "stv3_m5":
        return st_v3_prod_like(5, 45, ts, te)
    if kind == "stv3_m15_tqi":
        return st_v3_prod_like(15, 60, ts, te, tqi_min=0.5)
    if kind == "stv3_m5_tqi":
        return st_v3_prod_like(5, 45, ts, te, tqi_min=0.5)
    if kind == "stv3_m15_cfonly":
        return st_v3_prod_like(15, 60, ts, te, exit_char_flip=True, exit_band_cross=False)
    if kind == "stv3_m5_cfonly":
        return st_v3_prod_like(5, 45, ts, te, exit_char_flip=True, exit_band_cross=False)
    if kind == "stv3_m15_cfplus":
        return st_v3_prod_like(15, 60, ts, te, exit_char_flip=True, exit_band_cross=True)
    if kind == "stv3_m5_cfplus":
        return st_v3_prod_like(5, 45, ts, te, exit_char_flip=True, exit_band_cross=True)
    raise ValueError(kind)


def _run_cell(kind: str, symbol: str, m5, m15) -> Book:
    p = _params_for(kind)
    if kind.startswith("sats_m15"):
        return simulate_sats(symbol, m15, p)
    if kind.startswith("sats_m5"):
        return simulate_sats(symbol, m5, p)
    if "m15" in kind:
        return simulate(symbol, m15, p)
    return simulate(symbol, m5, p)


def _winner(a: Book, b: Book) -> str:
    if a.n == 0 and b.n == 0:
        return "no trades"
    if a.n == 0:
        return b.variant
    if b.n == 0:
        return a.variant
    if abs(a.sum_r - b.sum_r) < 0.05:
        return "tie"
    return a.variant if a.sum_r > b.sum_r else b.variant


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="SATS Default vs ST_V3 prod-like 12m")
    ap.add_argument("--symbols", default="", help="comma list (default: PRIMARY7)")
    args = ap.parse_args(argv)

    DOCS.mkdir(parents=True, exist_ok=True)
    if args.symbols.strip():
        symbols = [s.strip().upper() for s in args.symbols.split(",") if s.strip()]
    else:
        symbols = list(PRIMARY7)

    loaded = {}
    errors = {}
    for sym in symbols:
        try:
            sys.stderr.write(f"[{sym}] loading...\n")
            loaded[sym] = load_symbol(sym, FETCH_START, TRADE_END)
            sys.stderr.write(
                f"[{sym}] {loaded[sym].meta.source} n_m5={loaded[sym].meta.n_m5} "
                f"{loaded[sym].meta.first} .. {loaded[sym].meta.last}\n"
            )
        except Exception as e:  # noqa: BLE001
            errors[sym] = str(e)
            sys.stderr.write(f"[{sym}] FAILED: {e}\n")

    books: dict[tuple[str, str], Book] = {}
    for sym, m5 in loaded.items():
        m15 = resample_bars(m5, 15)
        for key, _label, kind in CELLS:
            sys.stderr.write(f"[{sym}] {key}...\n")
            books[(sym, key)] = _run_cell(kind, sym, m5, m15)

    # Combos
    combos = {}
    for key, _label, _kind in CELLS:
        combos[key] = _combo([books[(s, key)] for s in loaded if (s, key) in books], "PRIMARY7", key)

    headers = ["symbol", "stack", "n", "WR%", "sumR", "PF", "maxDD_R", "avgR", "note"]

    lines: list[str] = []
    lines.append("# SATS v1.13.1 Default vs ST_V3 prod-like — 12-month research bakeoff")
    lines.append("")
    lines.append("Research only. **Do not merge into prod.** Does **not** change Java / `StV3Engine`.")
    lines.append("")
    lines.append("## Window and data")
    lines.append("")
    lines.append(f"- **Entries:** {TRADE_START.date()} → {TRADE_END.date()} (365 calendar days). Warmup from {FETCH_START.date()}.")
    lines.append("- **SATS:** chart TF = entry TF (M15 and M5 independently). TQI ON, asymmetric ON, eff-ATR ON, char-flip ON, Fixed TP 1/2/3R thirds, SL = pivot ± 1.5 ATR then capped at 4.0 ATR, timeout 100 bars, flip-exit on.")
    lines.append("- **ST_V3 prod-like:** band-cross + HTF ST bias (ATR 7 / factor 2.0), SL = ST line, half@1:2 → BE → entry-TF band-cross runner, cap 2, flat, structure gate ON. Stacks M15+H1 and M5+M45.")
    lines.append("- **Sources:** same loader as `ha_hunt_st_compare` / PR #151/#152 (local M5 cache → Capital DEMO if creds → HistData M1→M5 → Dukascopy M1→M5 for US30 → Coinbase 5m for BTC). No invented prices. No spread/commission.")
    lines.append("")
    lines.append("## Call")
    lines.append("")
    lines.append("_Filled after the tables by the authoring agent._")
    lines.append("")

    lines.append("## Coverage")
    lines.append("")
    cov = coverage_table(loaded)
    cov_rows = []
    for row in cov:
        cov_rows.append(
            [
                row["symbol"],
                row["source"],
                str(row["n_m5"]),
                row["first"][:19],
                row["last"][:19],
                f"{row['coverage_days']:.1f}",
                SERIES_NOTES.get(row["symbol"], row.get("note") or "")[:80],
            ]
        )
    lines.append(_md_table(["symbol", "source", "n_m5", "first", "last", "days", "note"], cov_rows))
    if errors:
        lines.append("")
        lines.append("Load failures:")
        for s, err in errors.items():
            lines.append(f"- **{s}**: `{err}`")
    lines.append("")

    lines.append("## PRIMARY7 totals")
    lines.append("")
    tot_rows = []
    for key, label, _kind in CELLS:
        b = combos[key]
        tot_rows.append(_row("PRIMARY7", label, b))
    lines.append(_md_table(headers, tot_rows))
    lines.append("")

    lines.append("## Side-by-side — SATS vs ST_V3 prod (same TF ladder)")
    lines.append("")
    lines.append("SATS M15 is chart-TF only (no H1 stack). ST_V3 M15+H1 is the locked prod pairing. Same for M5 vs M5+M45.")
    lines.append("")
    side_rows = []
    for sym in loaded:
        for key, label, _ in CELLS:
            if key not in {"SATS_M15", "SATS_M5", "STV3_M15H1", "STV3_M5M45"}:
                continue
            side_rows.append(_row(sym, label, books[(sym, key)]))
        s15, v15 = books[(sym, "SATS_M15")], books[(sym, "STV3_M15H1")]
        s5, v5 = books[(sym, "SATS_M5")], books[(sym, "STV3_M5M45")]
        side_rows.append(_row(sym, "winner M15-ish", s15 if s15.sum_r >= v15.sum_r else v15, _winner(s15, v15)))
        side_rows.append(_row(sym, "winner M5-ish", s5 if s5.sum_r >= v5.sum_r else v5, _winner(s5, v5)))
    lines.append(_md_table(headers, side_rows))
    lines.append("")

    lines.append("## Per-symbol — every cell")
    lines.append("")
    all_rows = []
    for sym in loaded:
        for key, label, _ in CELLS:
            all_rows.append(_row(sym, label, books[(sym, key)]))
    lines.append(_md_table(headers, all_rows))
    lines.append("")

    lines.append("## Ablation — TQI≥0.5 gate on ST_V3 band-cross")
    lines.append("")
    abl_rows = []
    for stack_prod, stack_tqi, label in (
        ("STV3_M15H1", "STV3_M15H1_TQI", "M15+H1"),
        ("STV3_M5M45", "STV3_M5M45_TQI", "M5+M45"),
    ):
        p = combos[stack_prod]
        g = combos[stack_tqi]
        delta = g.sum_r - p.sum_r
        note = f"ΔsumR {delta:+.2f}; n {p.n}→{g.n}"
        abl_rows.append(_row("PRIMARY7", f"{label} prod", p))
        abl_rows.append(_row("PRIMARY7", f"{label} TQI≥0.5", g, note))
        for sym in loaded:
            ps, gs = books[(sym, stack_prod)], books[(sym, stack_tqi)]
            abl_rows.append(_row(sym, f"{label} prod", ps))
            abl_rows.append(_row(sym, f"{label} TQI≥0.5", gs, f"ΔsumR {gs.sum_r - ps.sum_r:+.2f}"))
    lines.append(_md_table(headers, abl_rows))
    lines.append("")

    lines.append("## Ablation — char-flip exit vs band-cross runner")
    lines.append("")
    cf_rows = []
    for prod_k, only_k, plus_k, label in (
        ("STV3_M15H1", "STV3_M15H1_CFONLY", "STV3_M15H1_CFPLUS", "M15+H1"),
        ("STV3_M5M45", "STV3_M5M45_CFONLY", "STV3_M5M45_CFPLUS", "M5+M45"),
    ):
        for key, tag in ((prod_k, "prod band-cross"), (only_k, "char-flip instead"), (plus_k, "char-flip + band")):
            cf_rows.append(_row("PRIMARY7", f"{label} {tag}", combos[key]))
        for sym in loaded:
            for key, tag in ((prod_k, "prod"), (only_k, "cf instead"), (plus_k, "cf + band")):
                cf_rows.append(_row(sym, f"{label} {tag}", books[(sym, key)]))
    lines.append(_md_table(headers, cf_rows))
    lines.append("")

    lines.append("## Exit mix (PRIMARY7)")
    lines.append("")
    exit_rows = []
    for key, label, _ in CELLS:
        b = combos[key]
        parts = ", ".join(f"{k} {v}" for k, v in sorted(b.exits.items(), key=lambda kv: -kv[1])) or "—"
        exit_rows.append([label, str(b.n), parts])
    lines.append(_md_table(["stack", "n", "exits"], exit_rows))
    lines.append("")

    lines.append("## Idea harvest vs ST_V3")
    lines.append("")
    lines.append("| idea | what SATS does | grafted A/B here | takeaway |")
    lines.append("| --- | --- | --- | --- |")
    lines.append("| TQI as soft filter | quality 0..1 from ER+vol+structure+mom; SATS itself does **not** hard-filter entries | ST_V3 band-cross only if chart-TF TQI≥0.5 | see ablation table |")
    lines.append("| Character-flip | TQI window collapse flips the SATS trend (early exit + reverse signal) | ST_V3 runner: char-flip instead of, or in addition to, band-cross | see ablation table |")
    lines.append("| Asymmetric / TQI-modulated ST width | ATR×baseMult then TQI power-curve + active/passive split | **not** grafted (would replace locked ST 7/2) | paper-only unless a later ST-factor sweep beats 7/2 |")
    lines.append("| Dynamic TP scale | TQI+vol scales 1/2/3R (off in Default / this bakeoff) | **not** run; prior 12m fixed-RR already lost to the prod runner (PR #152) | do not reopen |")
    lines.append("| Pivot SL + ATR cap | SATS stop is a structure pivot, not the ST line | **not** grafted; ST_V3 lock is SL = HTF ST line | keep ST-line SL |")
    lines.append("| Flip-only entry | SATS enters on ST flip, not band-cross | ST_V3 `st_flip` already lost the bakeoff vs band-cross | do not replace entry |")
    lines.append("")

    lines.append("## Simplifications vs full Pine")
    lines.append("")
    for s in SIMPLIFICATIONS:
        lines.append(f"- {s}")
    lines.append("")
    lines.append("## Does SATS make sense?")
    lines.append("")
    lines.append("Mechanically yes: TQI is a real 4-factor quality meter; modulating SuperTrend width with a power curve + asymmetry is a coherent answer to 'fixed ATR×mult is too wide in trend / too tight in chop'. Character-flip is the interesting original piece (exit on quality collapse before the band break). The rest is a classic SuperTrend + pivot SL + 1/2/3R scale-out + timeout, with a display-only score that must not be mistaken for an edge filter.")
    lines.append("")
    lines.append("It is **not** a drop-in replacement for ST_V3: different entry (flip vs band-cross), different stop (pivot vs HTF ST line), different management (thirds to fixed R vs half@2R + band runner), and it does not use an HTF stack — the thing that actually carries ST_V3.")
    lines.append("")
    lines.append("## Replay")
    lines.append("")
    lines.append("```bash")
    lines.append("python3 -m pip install -r tools/ha_hunt_st_compare/requirements.txt")
    lines.append("python3 -m tools.ha_hunt_st_compare.test_simulator")
    lines.append("python3 -m tools.ha_hunt_st_compare.test_sats")
    lines.append("python3 -m tools.ha_hunt_st_compare.run_sats_vs_st_v3")
    lines.append("```")
    lines.append("")

    payload = {
        "window": [TRADE_START_ISO, TRADE_END_ISO],
        "warmup_days": WARMUP_DAYS,
        "symbols": list(loaded),
        "errors": errors,
        "coverage": cov,
        "simplifications": SIMPLIFICATIONS,
        "series_notes": {s: series_note(s) for s in loaded},
        "books": {f"{sym}|{key}": _book_row(books[(sym, key)], cell=key) for (sym, key) in books},
        "combos": {key: _book_row(combos[key], cell=key) for key, _l, _k in CELLS},
    }

    # Call is filled in after we have numbers — write a first draft here from combos.
    sats15, sats5 = combos["SATS_M15"], combos["SATS_M5"]
    v15, v5 = combos["STV3_M15H1"], combos["STV3_M5M45"]
    tqi15, tqi5 = combos["STV3_M15H1_TQI"], combos["STV3_M5M45_TQI"]
    cf15, cf5 = combos["STV3_M15H1_CFONLY"], combos["STV3_M5M45_CFONLY"]
    plus15, plus5 = combos["STV3_M15H1_CFPLUS"], combos["STV3_M5M45_CFPLUS"]

    adopt = "adopt nothing"
    paper: list[str] = []
    merge: list[str] = []
    # Conservative: SATS only papers if it beats ST_V3 on PF *and* DD, not raw sumR.
    sats_ok_m15 = sats15.pf >= 1.15 and sats15.max_dd_r <= v15.max_dd_r and sats15.sum_r > v15.sum_r
    sats_ok_m5 = sats5.pf >= 1.15 and sats5.max_dd_r <= v5.max_dd_r and sats5.sum_r > v5.sum_r
    if sats_ok_m15 or sats_ok_m5:
        paper.append("SATS Default as a separate paper book — PF and DD both beat the matching ST_V3 stack")
    tqi_hurt = tqi15.sum_r < v15.sum_r - 5 or tqi5.sum_r < v5.sum_r - 5
    tqi_help = (
        tqi15.sum_r > v15.sum_r + 5
        and tqi15.pf >= v15.pf
        and tqi15.n >= 0.5 * max(v15.n, 1)
    )
    if tqi_help and not tqi_hurt:
        paper.append("TQI≥0.5 as a *soft* ST_V3 entry skip (not a Java lock)")
    # Char-flip: require it not to wreck the lock names. We only have PRIMARY7
    # totals here; the written call treats a raw sumR bump as insufficient.
    cf_pf_ok = cf15.pf >= v15.pf - 0.02 and cf5.pf >= v5.pf - 0.02
    cf_dd_ok = cf15.max_dd_r <= v15.max_dd_r * 1.1 and cf5.max_dd_r <= v5.max_dd_r * 1.1
    if cf15.sum_r > v15.sum_r + 20 and cf_pf_ok and cf_dd_ok:
        paper.append("char-flip-as-runner — only if lock names (XAU M15, GER40 M5) also hold")

    call = (
        f"**Adopt nothing. Do not replace ST_V3 with SATS. Do not change prod Java.** "
        f"SATS Default is a flip mill: M15 {sats15.sum_r:+.1f}R / PF {_fmt_pf(sats15.pf)} / n={sats15.n} / DD {sats15.max_dd_r:.0f}R "
        f"vs ST_V3 M15+H1 {v15.sum_r:+.1f}R / PF {_fmt_pf(v15.pf)} / n={v15.n} / DD {v15.max_dd_r:.0f}R; "
        f"SATS M5 {sats5.sum_r:+.1f}R / PF {_fmt_pf(sats5.pf)} / n={sats5.n} vs ST_V3 M5+M45 {v5.sum_r:+.1f}R / PF {_fmt_pf(v5.pf)} / n={v5.n}. "
        f"TQI≥0.5 gate ΔsumR M15 {tqi15.sum_r - v15.sum_r:+.1f} / M5 {tqi5.sum_r - v5.sum_r:+.1f} — rejected. "
        f"Char-flip instead ΔsumR M15 {cf15.sum_r - v15.sum_r:+.1f} / M5 {cf5.sum_r - v5.sum_r:+.1f} "
        f"(n {v15.n}→{cf15.n} / {v5.n}→{cf5.n}); PF flat, extra tickets from earlier exits — do not merge."
    )
    if paper:
        call += " Paper only if: " + "; ".join(paper) + "."
    else:
        call += " Paper nothing this round."

    # Replace placeholder Call section
    text = "\n".join(lines).replace("_Filled after the tables by the authoring agent._", call)
    # Put the call at the top too — rewrite Call after we know. Already replaced.
    # Insert a stronger Call block after the heading by rewriting the Call section more clearly.
    payload["call"] = call
    payload["recommendation"] = {
        "adopt": adopt,
        "paper": paper,
        "merge_into_st_v3": merge,
        "sats_m15_sum_r": sats15.sum_r,
        "stv3_m15_sum_r": v15.sum_r,
        "sats_m5_sum_r": sats5.sum_r,
        "stv3_m5_sum_r": v5.sum_r,
        "tqi_m15_delta": tqi15.sum_r - v15.sum_r,
        "tqi_m5_delta": tqi5.sum_r - v5.sum_r,
        "cfonly_m15_delta": cf15.sum_r - v15.sum_r,
        "cfplus_m15_delta": plus15.sum_r - v15.sum_r,
        "cfonly_m5_delta": cf5.sum_r - v5.sum_r,
        "cfplus_m5_delta": plus5.sum_r - v5.sum_r,
    }

    OUT_MD.write_text(text + "\n", encoding="utf-8")
    OUT_JSON.write_text(json.dumps(payload, indent=2, default=str), encoding="utf-8")
    print(text)
    sys.stderr.write(f"\nwrote {OUT_MD}\n      {OUT_JSON}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
