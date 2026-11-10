#!/usr/bin/env python3
"""
qullamaggie_fetch.py - downloads ~6 years of DAILY candles (Capital.com demo, mid prices, tick-volume) for every ticker in
tools/qullamaggie_universe.txt into tools/.candle_cache/<TICKER>_DAY.csv. Missing epics are skipped and listed.

usage: python tools/qullamaggie_fetch.py [--years 6]
"""
from __future__ import annotations

import argparse
import os
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent


def tickers() -> list[str]:
    out = []
    for line in (HERE / "qullamaggie_universe.txt").read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.startswith("#"):
            out += line.split()
    return list(dict.fromkeys(out))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--years", type=int, default=6)
    a = ap.parse_args()
    for cand in (HERE.parent / ".env", HERE.parent.parent / "bot_mtg_new" / ".env"):
        if cand.exists():
            for line in cand.read_text(encoding="utf-8-sig").splitlines():
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    os.environ.setdefault(k.strip(), v.strip())
            break
    sys.path.insert(0, str(HERE))
    import capital_data as cd

    c = cd.Capital(demo=True)
    end = datetime.now(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)
    start = end - timedelta(days=365 * a.years)
    missing, ok = [], 0
    ts = tickers()
    for i, t in enumerate(ts, 1):
        try:
            bars = c.candles(t, "DAY", start, end)
            if len(bars) < 300:
                missing.append(f"{t}(short:{len(bars)})")
            else:
                ok += 1
        except Exception as e:  # noqa: BLE001
            missing.append(f"{t}({type(e).__name__})")
        if i % 20 == 0:
            print(f"{i}/{len(ts)} done, ok={ok}", flush=True)
    print(f"DONE ok={ok} missing={len(missing)}: {' '.join(missing)}", flush=True)


if __name__ == "__main__":
    main()
