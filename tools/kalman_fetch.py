#!/usr/bin/env python3
"""
kalman_fetch.py - Capital.com M15 + H1 candles for US100, US500, BTCUSD (~14 months) into tools/.candle_cache/.

usage: python tools/kalman_fetch.py
"""
from __future__ import annotations

import os
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent


def main() -> None:
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
    end = datetime.now(timezone.utc).replace(microsecond=0) - timedelta(minutes=5)
    start = end - timedelta(days=430)
    for epic in ("US100", "US500", "BTCUSD"):
        for res in ("HOUR", "MINUTE_15"):
            try:
                bars = c.candles(epic, res, start, end)
                print(f"{epic} {res}: {len(bars)} bars {bars[0]['time']:%Y-%m-%d} .. {bars[-1]['time']:%Y-%m-%d %H:%M}", flush=True)
            except Exception as e:  # noqa: BLE001
                print(f"{epic} {res}: ERROR {type(e).__name__} {e}", flush=True)
    print("DONE", flush=True)


if __name__ == "__main__":
    main()
