#!/usr/bin/env python3
"""
equity_simulator.py — symulator equity curve / max drawdown na bazie danych
transakcyjnych (trade-level) dla strategii SDD-M15 / H1 swing (projekt mtg-bot).

Powstał, bo agregatowe liczby (Win%, avgR, PF) NIE wystarczają do policzenia
prawdziwego compoundowania i max drawdown — do tego potrzeba sekwencji
transakcji w czasie (kolejność ma znaczenie dla DD, nie ma znaczenia dla
finalnego zwrotu przy compoundowaniu proporcjonalnym do equity).

Dwa tryby:
  --mode per-symbol   -> osobna krzywa equity dla każdego symbolu (start = --capital każdy)
  --mode portfolio    -> JEDNA wspólna krzywa equity dla wszystkich symboli razem,
                         sortowane chronologicznie po entry_time, ryzyko liczone
                         jako % AKTUALNEGO wspólnego equity w momencie wejścia.
                         To jest realistyczny model prawdziwego konta bota
                         (RiskPolicy liczy 1% *aktualnego* equity konta, nie per-symbol).

Wymagany format CSV (jeden plik na scenariusz, np. trades_rr25.csv / trades_rr11.csv):

    entry_time,exit_time,symbol,direction,result,r_multiple
    2026-07-30T09:15:00,2026-07-30T13:45:00,EURUSD,LONG,WIN,1.0
    2026-07-30T10:00:00,2026-07-30T16:00:00,BTC,SHORT,LOSS,-2.5
    ...

- entry_time: ISO8601, wymagane (do sortowania chronologicznego / trybu portfolio)
- exit_time: opcjonalne (informacyjne, nieużywane w liczeniu equity)
- symbol: nazwa instrumentu
- direction: opcjonalne, informacyjne
- result: WIN / LOSS / BE (break-even, r_multiple=0) — użyte tylko jeśli brak r_multiple
- r_multiple: dokładny wynik transakcji w jednostkach R (zalecane — z realnego backtestu).
  Jeśli brak kolumny, użyte zostaną --win-r / --loss-r jako fallback wg `result`.

Użycie:
  python3 equity_simulator.py --mode portfolio --capital 10000 --risk 0.01 \
      trades_rr25.csv:2.5R trades_rr11.csv:1:1

  (etykieta po dwukropku to nazwa scenariusza używana w wykresie/raporcie)

Reguły ryzyka (opcjonalne, do modelowania T5 z filmów HTS):
  --day-stop N   po N stratnych tradach POD RZĄD w jednym dniu kalendarzowym
                 pomiń resztę tradów tego dnia (licznik zeruje wygrana / nowy
                 dzień). Kolumny summary: skipped_daystop.
  --max-dd PCT   gdy drawdown od szczytu ≥ PCT% → close-all i STOP całej
                 symulacji (twardy DD, np. 20). Kolumny: skipped_ddstop, ddstop_hit.

Wyjście:
  - equity_<scenario>.csv  (pełna krzywa equity: trade#, time, symbol, r, equity, drawdown%)
  - summary.csv            (zbiorcze metryki per scenariusz [+ per symbol w trybie per-symbol])
  - equity_curves.png      (wykres porównawczy, jeśli matplotlib dostępny)
"""

import argparse
import csv
import sys
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path


def parse_time(s: str) -> datetime:
    s = s.strip()
    for fmt in ("%Y-%m-%dT%H:%M:%S", "%Y-%m-%d %H:%M:%S", "%Y-%m-%d"):
        try:
            return datetime.strptime(s, fmt)
        except ValueError:
            continue
    # ostatnia deska ratunku: fromisoformat radzi sobie z większością wariantów
    return datetime.fromisoformat(s)


@dataclass
class Trade:
    entry_time: datetime
    symbol: str
    r_multiple: float
    exit_time: datetime | None = None
    direction: str | None = None
    result: str | None = None


def load_trades(path: str, win_r: float, loss_r: float) -> list[Trade]:
    trades = []
    with open(path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        required = {"entry_time", "symbol"}
        missing = required - set(reader.fieldnames or [])
        if missing:
            sys.exit(f"[{path}] brakuje wymaganych kolumn: {missing}. "
                     f"Znalezione: {reader.fieldnames}")
        for row in reader:
            r_raw = (row.get("r_multiple") or "").strip()
            if r_raw:
                r = float(r_raw)
            else:
                result = (row.get("result") or "").strip().upper()
                if result == "WIN":
                    r = win_r
                elif result == "LOSS":
                    r = -loss_r
                elif result in ("BE", "BREAKEVEN", ""):
                    r = 0.0
                else:
                    sys.exit(f"[{path}] nieznany 'result'={result!r} bez r_multiple w wierszu: {row}")
            trades.append(Trade(
                entry_time=parse_time(row["entry_time"]),
                symbol=row["symbol"].strip(),
                r_multiple=r,
                exit_time=parse_time(row["exit_time"]) if row.get("exit_time") else None,
                direction=row.get("direction"),
                result=row.get("result"),
            ))
    trades.sort(key=lambda t: t.entry_time)
    return trades


@dataclass
class SimResult:
    label: str
    symbol: str  # "PORTFOLIO" w trybie portfolio
    equity_curve: list[float] = field(default_factory=list)
    dd_curve: list[float] = field(default_factory=list)
    times: list[datetime] = field(default_factory=list)
    trades: list[Trade] = field(default_factory=list)
    skipped_daystop: int = 0   # trady pominięte przez regułę „2 straty pod rząd → stop dnia"
    skipped_ddstop: int = 0    # trady pominięte po zadziałaniu twardego DD (close-all)
    dd_stop_hit: bool = False

    def summary(self) -> dict:
        eq = self.equity_curve
        if not eq:
            return {}
        start = eq[0]
        final = eq[-1]
        total_return = (final / start - 1) * 100
        peak = start
        max_dd = 0.0
        for v in eq:
            peak = max(peak, v)
            dd = (peak - v) / peak * 100
            max_dd = max(max_dd, dd)
        wins = [t for t in self.trades if t.r_multiple > 0]
        losses = [t for t in self.trades if t.r_multiple < 0]
        n = len(self.trades)
        win_rate = len(wins) / n * 100 if n else 0.0
        avg_r = sum(t.r_multiple for t in self.trades) / n if n else 0.0
        gross_win = sum(t.r_multiple for t in wins)
        gross_loss = -sum(t.r_multiple for t in losses)
        pf = (gross_win / gross_loss) if gross_loss > 0 else float("inf")
        calmar = (total_return / max_dd) if max_dd > 0 else float("inf")
        return {
            "label": self.label,
            "symbol": self.symbol,
            "n_trades": n,
            "win_rate_%": round(win_rate, 1),
            "avg_R": round(avg_r, 3),
            "profit_factor": round(pf, 2) if pf != float("inf") else "inf",
            "start_equity": round(start, 2),
            "final_equity": round(final, 2),
            "total_return_%": round(total_return, 2),
            "max_drawdown_%": round(max_dd, 2),
            "return_over_maxdd": round(calmar, 2) if calmar != float("inf") else "inf",
            "skipped_daystop": self.skipped_daystop,
            "skipped_ddstop": self.skipped_ddstop,
            "ddstop_hit": self.dd_stop_hit,
        }


def simulate(trades: list[Trade], capital: float, risk_pct: float, label: str, symbol: str,
             day_stop: int = 0, max_dd_pct: float = 0.0) -> SimResult:
    """
    day_stop  > 0 : po `day_stop` stratnych tradach POD RZĄD w obrębie jednego
                    dnia kalendarzowego pomiń resztę tradów tego dnia (licznik
                    zeruje wygrana albo nowy dzień) — reguła „2 straty → stop dnia".
    max_dd_pct > 0: gdy drawdown od szczytu ≥ max_dd_pct — close-all i STOP całej
                    symulacji (twardy DD 20% z filmu 3). Kolejne trady pominięte.
    """
    res = SimResult(label=label, symbol=symbol)
    equity = capital
    peak = capital
    res.equity_curve.append(equity)
    res.dd_curve.append(0.0)
    cur_day = None
    consec_losses = 0
    day_halted = False
    for t in trades:
        day = t.entry_time.date()
        if day != cur_day:
            cur_day = day
            consec_losses = 0
            day_halted = False
        if res.dd_stop_hit:
            res.skipped_ddstop += 1
            continue
        if day_stop and day_halted:
            res.skipped_daystop += 1
            continue
        # ryzyko liczone jako % AKTUALNEGO equity w momencie wejścia (compounding),
        # zgodnie z RiskPolicy.riskAmount() w kodzie bota (1% aktualnego salda).
        equity = equity * (1 + risk_pct * t.r_multiple)
        peak = max(peak, equity)
        dd = (peak - equity) / peak * 100 if peak > 0 else 0.0
        res.equity_curve.append(equity)
        res.dd_curve.append(dd)
        res.times.append(t.entry_time)
        res.trades.append(t)
        if t.r_multiple < 0:
            consec_losses += 1
            if day_stop and consec_losses >= day_stop:
                day_halted = True
        elif t.r_multiple > 0:
            consec_losses = 0
        if max_dd_pct and dd >= max_dd_pct:
            res.dd_stop_hit = True
    return res


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("files", nargs="+", help="pliki CSV w formacie path:Etykieta (np. trades_rr25.csv:2.5R)")
    ap.add_argument("--mode", choices=["per-symbol", "portfolio"], default="portfolio")
    ap.add_argument("--capital", type=float, default=10000.0)
    ap.add_argument("--risk", type=float, default=0.01, help="ryzyko na trade jako ułamek equity (0.01 = 1%%)")
    ap.add_argument("--win-r", type=float, default=1.0, help="fallback R dla WIN gdy brak r_multiple w CSV")
    ap.add_argument("--loss-r", type=float, default=2.5, help="fallback R dla LOSS gdy brak r_multiple w CSV")
    ap.add_argument("--day-stop", type=int, default=0,
                    help="N stratnych tradów pod rząd w jednym dniu → pomiń resztę dnia (0 = wyłączone)")
    ap.add_argument("--max-dd", type=float, default=0.0,
                    help="twardy DD od szczytu w %% → close-all i stop symulacji (0 = wyłączone)")
    ap.add_argument("--out-dir", default=".")
    args = ap.parse_args()

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    scenarios = []
    for spec in args.files:
        if ":" in spec:
            path, label = spec.split(":", 1)
        else:
            path, label = spec, Path(spec).stem
        scenarios.append((path, label))

    all_summaries = []
    plot_series = []  # (label_for_plot, times, equity)

    for path, label in scenarios:
        trades = load_trades(path, args.win_r, args.loss_r)
        if not trades:
            print(f"[UWAGA] {path}: 0 transakcji, pomijam.")
            continue

        if args.mode == "portfolio":
            res = simulate(trades, args.capital, args.risk, label, "PORTFOLIO",
                           day_stop=args.day_stop, max_dd_pct=args.max_dd)
            all_summaries.append(res.summary())
            _write_equity_csv(res, out_dir / f"equity_{_safe(label)}_portfolio.csv")
            plot_series.append((label, res.times, res.equity_curve[1:]))
        else:
            by_symbol: dict[str, list[Trade]] = {}
            for t in trades:
                by_symbol.setdefault(t.symbol, []).append(t)
            for symbol, sym_trades in by_symbol.items():
                res = simulate(sym_trades, args.capital, args.risk, label, symbol,
                               day_stop=args.day_stop, max_dd_pct=args.max_dd)
                all_summaries.append(res.summary())
                _write_equity_csv(res, out_dir / f"equity_{_safe(label)}_{_safe(symbol)}.csv")
                plot_series.append((f"{label}/{symbol}", res.times, res.equity_curve[1:]))

    # summary.csv
    if all_summaries:
        keys = list(all_summaries[0].keys())
        with open(out_dir / "summary.csv", "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=keys)
            w.writeheader()
            for s in all_summaries:
                w.writerow(s)
        print("\n=== PODSUMOWANIE ===")
        col_w = {k: max(len(k), max(len(str(s[k])) for s in all_summaries)) for k in keys}
        print(" | ".join(k.ljust(col_w[k]) for k in keys))
        for s in all_summaries:
            print(" | ".join(str(s[k]).ljust(col_w[k]) for k in keys))
        print(f"\nZapisano: {out_dir / 'summary.csv'}")

    # wykres
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt

        fig, ax = plt.subplots(figsize=(11, 6))
        for label, times, equity in plot_series:
            ax.plot(times, equity, label=label, linewidth=1.6)
        ax.set_title("Equity curve — porównanie scenariuszy (compounding, % ryzyka/trade)")
        ax.set_xlabel("czas")
        ax.set_ylabel("equity")
        ax.axhline(args.capital, color="gray", linestyle="--", linewidth=0.8, label="start")
        ax.legend(fontsize=8)
        ax.grid(alpha=0.3)
        fig.autofmt_xdate()
        fig.tight_layout()
        fig.savefig(out_dir / "equity_curves.png", dpi=150)
        print(f"Zapisano wykres: {out_dir / 'equity_curves.png'}")
    except ImportError:
        print("[info] matplotlib niedostępny — pominięto wykres (equity_*.csv i tak zapisane).")


def _write_equity_csv(res: SimResult, path: Path):
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["trade_no", "entry_time", "symbol", "r_multiple", "equity", "drawdown_%"])
        w.writerow([0, "", "", "", res.equity_curve[0], 0.0])
        for i, t in enumerate(res.trades, start=1):
            w.writerow([i, t.entry_time.isoformat(), t.symbol, t.r_multiple,
                        round(res.equity_curve[i], 2), round(res.dd_curve[i], 2)])


def _safe(s: str) -> str:
    return "".join(c if c.isalnum() else "_" for c in s)


if __name__ == "__main__":
    main()
