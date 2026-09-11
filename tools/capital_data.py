#!/usr/bin/env python3
"""
capital_data.py — minimal Capital.com price-history client for backtests.

Ports the auth + chunked candle fetch from
server/.../broker/capital/CapitalComBrokerClient.java so the Python research
backtests pull the *same* mid-price bars the live bot sees.

  - POST /api/v1/session   header X-CAP-API-KEY, body {identifier,password,encryptedPassword:false}
    -> response headers CST + X-SECURITY-TOKEN
  - GET  /api/v1/prices/{epic}?resolution=..&max=1000&from=..&to=..
    price = (bid+ask)/2 for O/H/L/C  (mid, exactly like CapitalComBrokerClient.mid())

Candles are cached on disk under tools/.candle_cache/ so re-runs don't hammer
the feed (Capital rate-limits /prices hard).

Creds come from bot_mtg_new/.env (CAPITAL_DEMO_API_KEY / _EMAIL / _API_PASSWORD)
or the same-named environment variables.
"""
from __future__ import annotations

import csv
import os
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

import requests

try:  # use the OS trust store (Windows AV / proxy roots break certifi)
    import truststore

    truststore.inject_into_ssl()
except Exception:  # noqa: BLE001
    pass

DEMO_HOST = "https://demo-api-capital.backend-capital.com"
LIVE_HOST = "https://api-capital.backend-capital.com"

CACHE_DIR = Path(__file__).resolve().parent / ".candle_cache"

# resolution -> (Capital token, bucket seconds, safe chunk days per request)
RES = {
    "MINUTE_5": ("MINUTE_5", 300, 3),
    "MINUTE_15": ("MINUTE_15", 900, 10),
    "MINUTE_30": ("MINUTE_30", 1800, 20),
    "HOUR": ("HOUR", 3600, 30),
    "HOUR_4": ("HOUR_4", 14400, 60),
    "DAY": ("DAY", 86400, 365),
}

_TIME_FMT = "%Y-%m-%dT%H:%M:%S"


def _load_env() -> None:
    env = Path(__file__).resolve().parent.parent / ".env"
    if not env.exists():
        return
    for line in env.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip())


class Capital:
    def __init__(self, demo: bool = True):
        _load_env()
        self.host = DEMO_HOST if demo else LIVE_HOST
        pfx = "CAPITAL_DEMO_" if demo else "CAPITAL_LIVE_"
        self.api_key = os.environ.get(pfx + "API_KEY") or os.environ.get("CAPITAL_API_KEY")
        self.email = (
            os.environ.get(pfx + "EMAIL")
            or os.environ.get("CAPITAL_EMAIL")
            or os.environ.get("CAPITAL_DEMO_EMAIL")
        )
        self.password = (
            os.environ.get(pfx + "API_PASSWORD")
            or os.environ.get("CAPITAL_API_PASSWORD")
            or os.environ.get(pfx + "PASSWORD")
        )
        if not (self.api_key and self.email and self.password):
            sys.exit(
                "missing Capital creds — set CAPITAL_DEMO_API_KEY / CAPITAL_DEMO_EMAIL / "
                "CAPITAL_DEMO_API_PASSWORD in bot_mtg_new/.env"
            )
        self.cst = None
        self.tok = None
        self.s = requests.Session()

    def login(self) -> None:
        for attempt in range(5):
            r = self.s.post(
                f"{self.host}/api/v1/session",
                headers={"X-CAP-API-KEY": self.api_key},
                json={"identifier": self.email, "password": self.password, "encryptedPassword": False},
                timeout=30,
            )
            if r.status_code in (429, 503):
                time.sleep(2 ** attempt)
                continue
            r.raise_for_status()
            self.cst = r.headers.get("CST")
            self.tok = r.headers.get("X-SECURITY-TOKEN")
            if not (self.cst and self.tok):
                sys.exit("Capital session did not return CST / X-SECURITY-TOKEN")
            return
        sys.exit("Capital login kept rate-limiting (429/503)")

    def _headers(self) -> dict:
        return {"X-CAP-API-KEY": self.api_key, "CST": self.cst, "X-SECURITY-TOKEN": self.tok}

    def _fetch_window(self, epic: str, res: str, frm: datetime, to: datetime) -> list[dict]:
        tok, _, _ = RES[res]
        for attempt in range(5):
            r = self.s.get(
                f"{self.host}/api/v1/prices/{epic}",
                headers=self._headers(),
                params={
                    "resolution": tok,
                    "max": 1000,
                    "from": frm.strftime(_TIME_FMT),
                    "to": to.strftime(_TIME_FMT),
                },
                timeout=60,
            )
            if r.status_code == 429 and attempt < 4:
                time.sleep(2 ** attempt)
                continue
            if r.status_code == 404:
                raise RuntimeError(f"epic not found: {epic}")
            r.raise_for_status()
            out = []
            for p in (r.json().get("prices") or []):
                t = (p.get("snapshotTimeUTC") or p.get("snapshotTime") or "").replace(" ", "T")[:19]
                out.append(
                    {
                        "time": datetime.strptime(t, _TIME_FMT).replace(tzinfo=timezone.utc),
                        "open": _mid(p.get("openPrice")),
                        "high": _mid(p.get("highPrice")),
                        "low": _mid(p.get("lowPrice")),
                        "close": _mid(p.get("closePrice")),
                        "volume": p.get("lastTradedVolume") or 0,
                    }
                )
            return out
        return []

    def candles(self, epic: str, res: str, start: datetime, end: datetime) -> list[dict]:
        """Chunked [start, end) fetch, de-duped and sorted. Disk-cached."""
        CACHE_DIR.mkdir(exist_ok=True)
        cache = CACHE_DIR / f"{epic}_{res}.csv"
        have: dict[datetime, dict] = {}
        if cache.exists():
            with cache.open(newline="", encoding="utf-8") as fh:
                for row in csv.DictReader(fh):
                    ts = datetime.fromisoformat(row["time"])
                    have[ts] = {
                        "time": ts,
                        "open": float(row["open"]),
                        "high": float(row["high"]),
                        "low": float(row["low"]),
                        "close": float(row["close"]),
                        "volume": float(row["volume"]),
                    }

        covered = sorted(have)
        _, bucket_s, _ = RES[res]
        # the cache "covers the head" if its first bar is within a few bucket-spans
        # (or a long weekend) of the requested start — markets are shut at many
        # exact start instants, so an exact-timestamp match is the wrong test.
        head_slack = timedelta(seconds=max(bucket_s * 4, 4 * 86400))
        head_ok = bool(covered) and covered[0] <= start + head_slack
        need_from = start
        if head_ok and covered[-1] >= start:
            need_from = covered[-1] + timedelta(seconds=1)
        if head_ok and need_from >= end:
            return [have[t] for t in sorted(have) if start <= t < end]

        if not self.cst:
            self.login()
        _, _, chunk_days = RES[res]
        step = timedelta(days=chunk_days)
        cur = need_from
        fetched = 0
        while cur < end:
            hi = min(cur + step, end)
            rows = self._fetch_window(epic, res, cur, hi)
            for row in rows:
                have[row["time"]] = row
            fetched += len(rows)
            sys.stderr.write(f"  {epic} {res} {cur:%Y-%m-%d}..{hi:%Y-%m-%d}: +{len(rows)}\n")
            cur = hi
            time.sleep(0.4)

        with cache.open("w", newline="", encoding="utf-8") as fh:
            w = csv.DictWriter(fh, fieldnames=["time", "open", "high", "low", "close", "volume"])
            w.writeheader()
            for t in sorted(have):
                row = dict(have[t])
                row["time"] = t.isoformat()
                w.writerow(row)
        sys.stderr.write(f"  {epic} {res}: cache now {len(have)} bars (fetched {fetched})\n")
        return [have[t] for t in sorted(have) if start <= t < end]


def _mid(pair) -> float:
    if not pair:
        return float("nan")
    bid, ask = pair.get("bid"), pair.get("ask")
    if bid is not None and ask is not None:
        return (bid + ask) / 2.0
    if bid is not None:
        return bid
    return ask if ask is not None else float("nan")


if __name__ == "__main__":
    # smoke test: last ~15 days of H1 for each SDD epic
    c = Capital(demo=True)
    c.login()
    end = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0)
    start = end - timedelta(days=15)
    for epic in ("DE40", "GOLD", "US100", "EURUSD", "BTCUSD"):
        try:
            bars = c.candles(epic, "HOUR", start, end)
            if bars:
                print(f"{epic:8s} {len(bars):4d} H1 bars  {bars[0]['time']:%Y-%m-%d %H:%M} .. "
                      f"{bars[-1]['time']:%Y-%m-%d %H:%M}  last close={bars[-1]['close']:.2f}")
            else:
                print(f"{epic:8s} no bars")
        except Exception as e:
            print(f"{epic:8s} ERROR {e}")
