#!/usr/bin/env python3
"""long_tp_stack_report.py - tables for docs/DAILY-SCALP-LONGONLY.md from long_tp_stack_bt.py output."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).parent))
import long_tp_stack_bt as bt  # noqa: E402

NL = chr(10)


def md(df: pd.DataFrame, nd: int = 2) -> str:
    cols = list(df.columns)
    out = ["| " + " | ".join(cols) + " |", "|" + "|".join(["---"] * len(cols)) + "|"]
    for _, r in df.iterrows():
        cells = []
        for c in cols:
            v = r[c]
            cells.append(f"{v:.{nd}f}" if isinstance(v, (float, np.floating)) else str(v))
        out.append("| " + " | ".join(cells) + " |")
    return NL.join(out)


def index_context(cache: Path) -> pd.DataFrame:
    rows = []
    for name, idx in bt.INDICES.items():
        df = bt.load(cache, idx)
        c = df["c"]
        lo = df["l"]
        runmax = c.cummax()
        dd = (lo / runmax - 1)
        t_dd = dd.idxmin()
        peak_t = c[:t_dd].idxmax()
        daily = c.groupby(c.index.normalize()).last()
        worst_day = (daily.pct_change()).min() * 100
        worst_week = (daily.pct_change(5)).min() * 100
        rows.append(dict(index=name, start=f"{c.iloc[0]:.0f}", end=f"{c.iloc[-1]:.0f}",
                         change_pct=(c.iloc[-1] / c.iloc[0] - 1) * 100,
                         max_drawdown_pct=dd.min() * 100, peak=str(peak_t.date()), trough=str(t_dd.date()),
                         worst_day_pct=worst_day, worst_5d_pct=worst_week))
    return pd.DataFrame(rows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", required=True)
    ap.add_argument("--results", default=str(Path(__file__).parent / "out" / "long_tp"))
    a = ap.parse_args()
    eq = pd.read_csv(a.results + "_equity.csv", parse_dates=["stopped", "wiped"])
    st = pd.read_csv(a.results + "_tpstats.csv")
    months = 25.6
    parts = []

    parts.append("## A. Kontekst: co indeksy zrobiły w próbie (M5, 09.2024–10.2026)" + NL)
    parts.append(md(index_context(Path(a.cache))))

    parts.append(NL + "## B. Ile może spaść indeks do wyrzucenia z rynku (teoria)" + NL)
    rows = []
    for L in (1, 2, 3, 5, 7, 10, 15, 20):
        stop = max(0.0, 1.0 / L - bt.CLOSEOUT * bt.MARGIN_RATE) * 100
        rows.append(dict(gross_leverage=f"{L}x", drop_to_stop_out_pct=stop, drop_to_zero_pct=100.0 / L))
    parts.append(md(pd.DataFrame(rows)))
    parts.append(NL + "Założenia: marża 5% (20:1), zamknięcie przez brokera przy kapitale ≤ 50% wymaganej marży "
                      "(ESMA, konto detaliczne). Gross leverage = łączny nominał otwartych pozycji / kapitał." + NL)

    parts.append(NL + "## C. Statystyka trafiania TP (long-only, bez SL, pozycje czekają)" + NL)
    s2 = st[st.k_max == 0].copy()
    s2["days_med"] = s2["med_days"]
    cols = ["index", "rule", "d_pct", "entries_pm", "tps_pm", "unresolved", "open_end", "med_days", "p95_days", "max_days",
            "mae_med", "mae_p95", "mae_worst"]
    parts.append("Wiersze: bez limitu pozycji. mae = największa chwilowa strata pozycji przed TP (% ceny wejścia)." + NL)
    parts.append(md(s2[cols].sort_values(["index", "rule", "d_pct"])))

    parts.append(NL + "## D. Konta, które przetrwały cały okres (bez stop-outu), posortowane po wyniku zrealizowanym" + NL)
    m = eq.merge(st[["index", "rule", "d_pct", "k_max", "entries_pm", "tps_pm", "med_days", "p95_days", "max_days",
                     "unresolved", "mae_worst"]], on=["index", "rule", "d_pct", "k_max"])
    m["ret_pm_pct"] = (m["end_realised"] - 1.0) * 100 / months
    ok = m[m.stopped.isna() & m.wiped.isna()].copy()
    parts.append(f"{len(ok)} z {len(m)} konfiguracji nie została zatrzymana przez brokera w całym okresie." + NL)
    cols = ["index", "rule", "d_pct", "k_max", "g_pct", "tps_pm", "ret_pm_pct", "max_open", "max_gross", "min_eq",
            "max_dd", "buffer_pct", "under_share", "med_days", "p95_days", "max_days", "unresolved", "mae_worst"]
    for g in (0.1, 0.2, 0.33, 0.5):
        for name in bt.INDICES:
            sub = ok[(ok.g_pct == g) & (ok["index"] == name)].sort_values("ret_pm_pct", ascending=False).head(5)
            if sub.empty:
                continue
            parts.append(NL + f"### {name}, g = {g}% konta na jeden TP" + NL)
            parts.append(md(sub[cols]))

    parts.append(NL + "## E. Ile konfiguracji zostało zatrzymanych (stop-out) wg g" + NL)
    s3 = m.assign(stopped_flag=~m.stopped.isna()).groupby(["index", "g_pct"]).agg(
        configs=("stopped_flag", "size"), stopped=("stopped_flag", "sum")).reset_index()
    s3["configs"] = s3["configs"].astype(int)
    s3["stopped"] = s3["stopped"].astype(int)
    parts.append(md(s3, 2))

    parts.append(NL + "## F. Wymagana częstotliwość dla 6% miesięcznie" + NL)
    rows = []
    for g in (0.1, 0.2, 0.33, 0.5, 1.0):
        need = 6.0 / g
        rows.append(dict(g_pct_per_tp=g, tp_per_month=need, tp_per_trading_day=need / 21.0,
                         per_index_per_day=need / 21.0 / 3.0))
    parts.append(md(pd.DataFrame(rows)))

    out = Path(a.results + "_report.md")
    out.write_text(NL.join(parts), encoding="utf-8")
    print("written", out)


if __name__ == "__main__":
    main()
