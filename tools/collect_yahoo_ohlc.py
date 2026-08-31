#!/usr/bin/env python3
"""Download public Yahoo OHLC for the HTS replay path (Capital creds missing).

Writes <epic>_<RES>.csv consumed by ReplayBrokerClient / HtsTfBakeoffTest.
H4 is resampled from 60m (Yahoo has no 4h interval). M5/M15 are Yahoo's ~60d cap.

  python3 tools/collect_yahoo_ohlc.py /tmp/hts-ohlc
"""
from __future__ import annotations

import json
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

# Same mapping as PR #95.
NAMES = {
    "DE40": "^GDAXI",
    "GOLD": "GC=F",
    "US100": "NQ=F",
    "EURUSD": "EURUSD=X",
    "BTCUSD": "BTC-USD",
}
# interval, yahoo_range, our Resolution name
SERIES = [
    ("5m", "60d", "M5"),
    ("15m", "60d", "M15"),
    ("60m", "1y", "H1"),
    ("1d", "2y", "D1"),
]
UA = {"User-Agent": "Mozilla/5.0 (research; +https://github.com/adammendak/bot_mtg_new)"}


def fetch(ticker: str, interval: str, range_: str) -> list[tuple]:
    q = urllib.parse.urlencode({"interval": interval, "range": range_, "includePrePost": "false"})
    url = f"https://query1.finance.yahoo.com/v8/finance/chart/{urllib.parse.quote(ticker)}?{q}"
    last = None
    for attempt in range(5):
        try:
            req = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(req, timeout=90) as r:
                data = json.loads(r.read().decode())
            err = (data.get("chart") or {}).get("error")
            if err:
                raise RuntimeError(err)
            res = (data.get("chart") or {}).get("result") or []
            if not res:
                return []
            r0 = res[0]
            ts = r0.get("timestamp") or []
            q0 = r0["indicators"]["quote"][0]
            vol = q0.get("volume") or [0] * len(ts)
            out = []
            for i, t in enumerate(ts):
                o, h, l, c = q0["open"][i], q0["high"][i], q0["low"][i], q0["close"][i]
                if None in (o, h, l, c):
                    continue
                out.append((int(t), float(o), float(h), float(l), float(c), float(vol[i] or 0)))
            return out
        except Exception as e:
            last = e
            time.sleep(2 ** attempt)
    raise last


def floor_ts(ts: int, minutes: int) -> datetime:
    dt = datetime.fromtimestamp(ts, tz=timezone.utc)
    m = (dt.minute // minutes) * minutes
    return dt.replace(minute=m, second=0, microsecond=0)


def write_csv(path: Path, rows: list[tuple], minutes: int | None) -> None:
    seen = {}
    for t, o, h, l, c, v in rows:
        key = floor_ts(t, minutes) if minutes else datetime.fromtimestamp(t, tz=timezone.utc).replace(
            hour=0, minute=0, second=0, microsecond=0
        )
        prev = seen.get(key)
        if prev is None:
            seen[key] = [key, o, h, l, c, v]
        else:
            prev[2] = max(prev[2], h)
            prev[3] = min(prev[3], l)
            prev[4] = c
            prev[5] += v
    ordered = [seen[k] for k in sorted(seen)]
    # drop an in-progress last bar that is still forming (seconds != 0 on raw feed)
    path.write_text(
        "time,open,high,low,close,volume\n"
        + "".join(
            f"{row[0].strftime('%Y-%m-%dT%H:%M:%SZ')},{row[1]},{row[2]},{row[3]},{row[4]},{row[5]}\n"
            for row in ordered
        )
    )


def resample_h4(h1_rows: list[tuple]) -> list[tuple]:
    buckets: dict[datetime, list] = {}
    for t, o, h, l, c, v in h1_rows:
        dt = datetime.fromtimestamp(t, tz=timezone.utc).replace(minute=0, second=0, microsecond=0)
        key = dt.replace(hour=(dt.hour // 4) * 4)
        buckets.setdefault(key, []).append((t, o, h, l, c, v))
    out = []
    for key in sorted(buckets):
        bars = buckets[key]
        out.append((int(key.timestamp()), bars[0][1], max(b[2] for b in bars),
                    min(b[3] for b in bars), bars[-1][4], sum(b[5] for b in bars)))
    return out


def main() -> int:
    out_dir = Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/hts-ohlc")
    out_dir.mkdir(parents=True, exist_ok=True)
    for epic, ticker in NAMES.items():
        h1 = None
        for interval, rng, res in SERIES:
            rows = fetch(ticker, interval, rng)
            minutes = {"M5": 5, "M15": 15, "H1": 60, "D1": None}[res]
            write_csv(out_dir / f"{epic}_{res}.csv", rows, minutes)
            first = datetime.fromtimestamp(rows[0][0], tz=timezone.utc) if rows else None
            last = datetime.fromtimestamp(rows[-1][0], tz=timezone.utc) if rows else None
            print(f"{epic:7} {res:3} n={len(rows):6} {first} .. {last}  ({ticker} {interval} {rng})")
            if res == "H1":
                h1 = rows
            time.sleep(0.35)
        h4 = resample_h4(h1 or [])
        write_csv(out_dir / f"{epic}_H4.csv", h4, 240)
        print(f"{epic:7} H4  n={len(h4):6}  (resampled from H1, UTC 4h buckets)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
