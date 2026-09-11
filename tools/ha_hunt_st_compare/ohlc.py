"""OHLC loaders: local cache, optional Capital DEMO mids, HistData M1, Coinbase BTC.

Capital DEMO credentials (``CAPITAL_API_KEY`` / ``CAPITAL_EMAIL`` /
``CAPITAL_API_PASSWORD``) are preferred when present. This environment did not
have them, so the bake-off falls back to real HistData M1 (resampled to M5) and
Coinbase Exchange 5m for BTC — never invented prices.
"""

from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Optional
from zoneinfo import ZoneInfo

import numpy as np
import pandas as pd

CACHE = Path(__file__).resolve().parent / "cache"
NY = ZoneInfo("America/New_York")
UTC = timezone.utc

# HistData ASCII pair → Adam / Capital epic
HISTDATA_PAIRS = {
    "XAU": "XAUUSD",
    "US100": "NSXUSD",
    "GER40": "GRXEUR",
    "EURUSD": "EURUSD",
}

# Capital.com epics (same defaults as application.properties)
CAPITAL_EPICS = {
    "XAU": "GOLD",
    "US100": "US100",
    "GER40": "DE40",
    "EURUSD": "EURUSD",
    "BTCUSD": "BTCUSD",
}

SYMBOLS = ["XAU", "US100", "EURUSD", "GER40", "BTCUSD"]


@dataclass
class SeriesMeta:
    symbol: str
    source: str
    path: str
    first: str
    last: str
    n_m1: int
    n_m5: int
    gaps_note: str
    coverage_days: float


@dataclass
class Bars:
    time: np.ndarray  # datetime64[ns] UTC bar open
    open: np.ndarray
    high: np.ndarray
    low: np.ndarray
    close: np.ndarray
    meta: SeriesMeta


def _ensure_cache() -> Path:
    CACHE.mkdir(parents=True, exist_ok=True)
    return CACHE


def resample_bars(bars: Bars, minutes: int) -> Bars:
    """Epoch-bucket resample (same rule as Java ``Resample`` / HTF aggregate)."""
    if minutes <= 0:
        raise ValueError("minutes must be > 0")
    sec = bars.time.astype("datetime64[s]").astype(np.int64)
    if len(sec) >= 2:
        step = int(np.median(np.diff(sec)))
        if step == minutes * 60:
            return bars
    span = minutes * 60
    starts = (sec // span) * span
    if len(starts) == 0:
        return bars
    change = np.empty(len(starts), dtype=bool)
    change[0] = True
    change[1:] = starts[1:] != starts[:-1]
    idx = np.nonzero(change)[0]
    ends = np.append(idx[1:], len(starts))
    n = len(idx)
    ho = np.empty(n, dtype=np.float64)
    hh = np.empty(n, dtype=np.float64)
    hl = np.empty(n, dtype=np.float64)
    hc = np.empty(n, dtype=np.float64)
    hs = starts[idx]
    o, h, l, c = bars.open, bars.high, bars.low, bars.close
    for i, (a, b) in enumerate(zip(idx, ends)):
        ho[i] = o[a]
        hh[i] = np.max(h[a:b])
        hl[i] = np.min(l[a:b])
        hc[i] = c[b - 1]
    times = hs.astype("datetime64[s]").astype("datetime64[ns]")
    meta = SeriesMeta(
        symbol=bars.meta.symbol,
        source=f"{bars.meta.source}_m{minutes}",
        path=bars.meta.path,
        first=str(times[0]) if n else bars.meta.first,
        last=str(times[-1]) if n else bars.meta.last,
        n_m1=bars.meta.n_m1,
        n_m5=n,
        gaps_note=f"{bars.meta.gaps_note}; resampled to M{minutes}",
        coverage_days=bars.meta.coverage_days,
    )
    return Bars(times, ho, hh, hl, hc, meta)


def resample_ohlc(df: pd.DataFrame, minutes: int) -> pd.DataFrame:
    """UTC epoch-bucket resample — same rule as Java ``Resample.bucket``."""
    if df.empty:
        return df
    out = (
        df.resample(f"{minutes}min", label="left", closed="left")
        .agg({"open": "first", "high": "max", "low": "min", "close": "last"})
        .dropna(subset=["open", "high", "low", "close"])
    )
    return out


def _histdata_to_utc(df: pd.DataFrame) -> pd.DataFrame:
    """HistData M1 stamps are US Eastern session time (Fri last bar ~16:58)."""
    ts = pd.to_datetime(df["datetime"])
    if ts.dt.tz is None:
        ts = ts.dt.tz_localize(NY, ambiguous="infer", nonexistent="shift_forward")
    ts = ts.dt.tz_convert("UTC")
    out = df[["open", "high", "low", "close"]].copy()
    out.index = ts
    out = out[~out.index.duplicated(keep="last")].sort_index()
    return out.astype(float)


def fetch_histdata_m1(symbol: str, start: str, end: str) -> pd.DataFrame:
    pair = HISTDATA_PAIRS[symbol]
    path = _ensure_cache() / f"{pair}_M1.csv"
    if path.exists() and path.stat().st_size > 1000:
        df = pd.read_csv(path)
    else:
        from histdata_fetcher import fetch_data

        result = fetch_data(pair, start, end, "1min", output_format="csv", output_path=str(path))
        df = result.data
        if not path.exists():
            df.to_csv(path, index=False)
    return _histdata_to_utc(df)


def fetch_coinbase_btc_m5(start: datetime, end: datetime) -> pd.DataFrame:
    """Public Coinbase Exchange 5m candles (real trades, UTC). Paginated."""
    path = _ensure_cache() / "BTCUSD_M5_coinbase.csv"
    if path.exists() and path.stat().st_size > 1000:
        df = pd.read_csv(path, parse_dates=["time"])
        df["time"] = pd.to_datetime(df["time"], utc=True)
        df = df.set_index("time").sort_index()
        return df[["open", "high", "low", "close"]].astype(float)

    rows: list[tuple] = []
    cursor = start
    url = "https://api.exchange.coinbase.com/products/BTC-USD/candles"
    while cursor < end:
        window_end = min(cursor + timedelta(hours=25), end)  # 300 * 5m = 25h
        q = (
            f"{url}?granularity=300"
            f"&start={cursor.strftime('%Y-%m-%dT%H:%M:%SZ')}"
            f"&end={window_end.strftime('%Y-%m-%dT%H:%M:%SZ')}"
        )
        req = urllib.request.Request(q, headers={"User-Agent": "ha-hunt-research"})
        last_err = None
        body = None
        for attempt in range(6):
            try:
                with urllib.request.urlopen(req, timeout=30) as resp:
                    body = json.loads(resp.read().decode())
                break
            except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as e:
                last_err = e
                time.sleep(1.5 * (attempt + 1))
        if body is None:
            raise RuntimeError(f"Coinbase BTC candles failed: {last_err}")
        # Coinbase: [time, low, high, open, close, volume]
        for item in body:
            t, low, high, o, c, _v = item
            rows.append((int(t), float(o), float(high), float(low), float(c)))
        cursor = window_end
        time.sleep(0.12)

    if not rows:
        raise RuntimeError("Coinbase returned no BTC candles")
    raw = pd.DataFrame(rows, columns=["ts", "open", "high", "low", "close"])
    raw["time"] = pd.to_datetime(raw["ts"], unit="s", utc=True)
    raw = raw.drop_duplicates("time").sort_values("time").set_index("time")
    raw = raw[["open", "high", "low", "close"]]
    raw.to_csv(path)
    return raw


def _capital_session() -> tuple[str, str, str]:
    key = os.environ.get("CAPITAL_DEMO_API_KEY") or os.environ.get("CAPITAL_API_KEY") or ""
    email = os.environ.get("CAPITAL_DEMO_EMAIL") or os.environ.get("CAPITAL_EMAIL") or ""
    password = os.environ.get("CAPITAL_DEMO_API_PASSWORD") or os.environ.get("CAPITAL_API_PASSWORD") or ""
    if not (key and email and password):
        raise RuntimeError("Capital DEMO credentials not set")
    host = os.environ.get("CAPITAL_DEMO_HOST", "https://demo-api-capital.backend-capital.com")
    payload = json.dumps({"identifier": email, "password": password}).encode()
    req = urllib.request.Request(
        f"{host.rstrip('/')}/api/v1/session",
        data=payload,
        headers={"X-CAP-API-KEY": key, "Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        cst = resp.headers.get("CST")
        tok = resp.headers.get("X-SECURITY-TOKEN")
    if not cst or not tok:
        raise RuntimeError("Capital session missing CST / X-SECURITY-TOKEN")
    return host.rstrip("/"), cst, tok


def fetch_capital_m5(symbol: str, start: datetime, end: datetime) -> pd.DataFrame:
    """Capital DEMO mid OHLC (bid/ask mid), MINUTE_5, 3-day chunks."""
    host, cst, tok = _capital_session()
    epic = CAPITAL_EPICS[symbol]
    headers = {"CST": cst, "X-SECURITY-TOKEN": tok, "X-CAP-API-KEY": os.environ.get("CAPITAL_API_KEY", "")}
    rows: list[dict] = []
    chunk_to = end
    guard = 0
    while chunk_to > start and guard < 500:
        guard += 1
        chunk_from = max(start, chunk_to - timedelta(days=3))
        q = (
            f"{host}/api/v1/prices/{epic}?resolution=MINUTE_5&max=1000"
            f"&from={chunk_from.strftime('%Y-%m-%dT%H:%M:%S')}"
            f"&to={chunk_to.strftime('%Y-%m-%dT%H:%M:%S')}"
        )
        req = urllib.request.Request(q, headers=headers)
        with urllib.request.urlopen(req, timeout=45) as resp:
            body = json.loads(resp.read().decode())
        prices = body.get("prices") or []
        for p in prices:
            t = p.get("snapshotTimeUTC") or p.get("snapshotTime")
            def mid(side: dict | None) -> Optional[float]:
                if not side:
                    return None
                bid, ask = side.get("bid"), side.get("ask")
                if bid is None and ask is None:
                    return None
                if bid is None:
                    return float(ask)
                if ask is None:
                    return float(bid)
                return (float(bid) + float(ask)) / 2.0
            o = mid(p.get("openPrice"))
            h = mid(p.get("highPrice"))
            l = mid(p.get("lowPrice"))
            c = mid(p.get("closePrice"))
            if None in (o, h, l, c):
                continue
            rows.append({"time": t, "open": o, "high": h, "low": l, "close": c})
        chunk_to = chunk_from
        time.sleep(0.25)
    if not rows:
        raise RuntimeError(f"Capital returned no MINUTE_5 mids for {epic}")
    df = pd.DataFrame(rows)
    df["time"] = pd.to_datetime(df["time"], utc=True)
    df = df.drop_duplicates("time").sort_values("time").set_index("time")
    return df[["open", "high", "low", "close"]].astype(float)


def load_symbol(symbol: str, start: datetime, end: datetime, prefer_capital: bool = True) -> Bars:
    source = ""
    m5: Optional[pd.DataFrame] = None
    n_m1 = 0
    gaps = []

    cache_m5 = _ensure_cache() / f"{symbol}_M5.csv"
    if cache_m5.exists() and cache_m5.stat().st_size > 1000:
        df = pd.read_csv(cache_m5, parse_dates=["time"])
        df["time"] = pd.to_datetime(df["time"], utc=True)
        m5 = df.set_index("time").sort_index()[["open", "high", "low", "close"]].astype(float)
        source = "local_m5_cache"
        meta_path = _ensure_cache() / f"{symbol}_meta.json"
        if meta_path.exists():
            extra = json.loads(meta_path.read_text())
            source = extra.get("source", source)
            n_m1 = int(extra.get("n_m1", 0))
            gaps = extra.get("gaps", [])

    if m5 is None and prefer_capital:
        try:
            m5 = fetch_capital_m5(symbol, start, end)
            source = "capital_demo_mid"
        except Exception as e:
            gaps.append(f"capital_unavailable: {e}")

    if m5 is None and symbol in HISTDATA_PAIRS:
        m1 = fetch_histdata_m1(symbol, start.date().isoformat(), end.date().isoformat())
        n_m1 = len(m1)
        m5 = resample_ohlc(m1, 5)
        source = "histdata_m1_resampled_m5"
        gaps.append("HistData M1 (Eastern stamps → UTC), resampled to M5. Not Capital mids.")

    if m5 is None and symbol == "BTCUSD":
        m5 = fetch_coinbase_btc_m5(start, end)
        source = "coinbase_exchange_m5"
        gaps.append("Coinbase Exchange BTC-USD 5m public candles. Not Capital mids. HistData has no BTC.")

    if m5 is None or m5.empty:
        raise RuntimeError(f"No real OHLC available for {symbol}")

    m5 = m5.loc[(m5.index >= pd.Timestamp(start)) & (m5.index <= pd.Timestamp(end))]
    if m5.empty:
        raise RuntimeError(f"{symbol}: cache/source has no bars in [{start}, {end}]")

    if not cache_m5.exists():
        tmp = m5.reset_index()
        time_col = tmp.columns[0]
        tmp = tmp.rename(columns={time_col: "time"})
        tmp.to_csv(cache_m5, index=False)
        (_ensure_cache() / f"{symbol}_meta.json").write_text(
            json.dumps({"source": source, "n_m1": n_m1, "gaps": gaps}, indent=2)
        )

    first = m5.index[0].isoformat()
    last = m5.index[-1].isoformat()
    span = (m5.index[-1] - m5.index[0]).total_seconds() / 86400.0
    meta = SeriesMeta(
        symbol=symbol,
        source=source,
        path=str(cache_m5),
        first=first,
        last=last,
        n_m1=n_m1,
        n_m5=len(m5),
        gaps_note="; ".join(gaps) if gaps else "",
        coverage_days=round(span, 2),
    )
    return Bars(
        time=m5.index.to_numpy(dtype="datetime64[ns]"),
        open=m5["open"].to_numpy(dtype=np.float64),
        high=m5["high"].to_numpy(dtype=np.float64),
        low=m5["low"].to_numpy(dtype=np.float64),
        close=m5["close"].to_numpy(dtype=np.float64),
        meta=meta,
    )


def coverage_table(bars_by_sym: dict[str, Bars]) -> list[dict]:
    rows = []
    for sym, b in bars_by_sym.items():
        rows.append(
            {
                "symbol": sym,
                "source": b.meta.source,
                "first": b.meta.first,
                "last": b.meta.last,
                "n_m5": b.meta.n_m5,
                "n_m1": b.meta.n_m1,
                "coverage_days": b.meta.coverage_days,
                "note": b.meta.gaps_note,
            }
        )
    return rows
