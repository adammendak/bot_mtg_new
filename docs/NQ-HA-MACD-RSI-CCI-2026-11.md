# NQ 5-min: Heikin Ashi + MACD(6,13,5) / RSI(7) / CCI(14) — analiza (2026-11)

Źródło: slajd z TraderLion Podcast (wykres 5 min NQ: świece HA, MACD 6/13/5, RSI 7, CCI 14, 200-DMA, VWAP).
Wniosek: **strategia nie ma przewagi na 25 miesiącach danych — bota nie piszemy.**

## Reguły (jak zrealizowane w teście)

- Świece HA liczone na ciągłych danych 5-min; wskaźniki liczone na HA (MACD, RSI, CCI), 200-SMA i VWAP na prawdziwych cenach.
- **Long**: MACD przecina linię sygnału w górę, RSI(7) > 50, CCI(14) > 0 (na zamknięciu świecy). **Short**: lustrzanie.
- Przeciwny sygnał zamyka i odwraca pozycję.
- Wejście/wyjście po **otwarciu następnej prawdziwej świecy** (nie po cenach HA — te są wygładzone i zawyżają wynik).
- Koszty: 0,25 pkt poślizgu na stronę + 2 USD prowizji na stronę (na kontrakt NQ, 20 USD/pkt).
- Tylko sesja 9:30–16:00 NY, flat na koniec dnia (zamknięcie na close świecy 15:55).
- **Wariant A**: sam sygnał. **Wariant B**: dodatkowo long tylko powyżej VWAP (kotwica 9:30 NY) i SMA200, short tylko poniżej obu.
- Dodatkowy tryb „state": sygnał, gdy wszystkie 3 warunki dopiero się spełnią (zamiast crossa MACD).

## Dane

Capital.com, US100 (mid), M5: 2024-09-11 … 2026-11-06 (156 660 świec, 25 miesięcy), po scaleniu cache z `bot_mtg_new` i nowego pobrania. Wolumen jest tickowy (CFD), więc VWAP jest przybliżony; to nie kontrakt futures NQ.

## Wyniki

### Ostatnie 40 dni (to, co pierwotnie wyglądało dobrze)

| | transakcje | PF | wynik USD/kontrakt |
|---|---|---|---|
| A | 84 | 0,99 | −862 |
| B | 37 | 1,27 | +11 930 |

US100 spadł w tym oknie o 12–14%, a cały zysk B to shorty (+1 055 pkt; longi −458 pkt). Próbka 37 transakcji w jednym trendzie.

### Cała historia (25 miesięcy, po kosztach)

| | transakcje | skuteczność | PF | wynik USD | max DD USD | long pkt | short pkt |
|---|---|---|---|---|---|---|---|
| A | 1 508 | 37,7% | 1,04 | +49 382 | 51 558 | +3 270 | −801 |
| B | 772 | 44,3% | 0,98 | **−21 000** | 99 578 | +1 428 | −2 478 |

Wrażliwość na koszty:

| | brak kosztów | poślizg 0,25 | 0,5 | 1,0 |
|---|---|---|---|---|
| A (PF) | 1,06 | 1,04 | 1,03 | 1,00 |
| B (PF) | 0,99 | 0,98 | 0,97 | 0,95 |

- **A**: przewaga przed kosztami jest minimalna (PF 1,06), przy poślizgu 1 pkt znika całkiem. Na kontrakcie CFD ze spreadem Capital.com realny poślizg to raczej ≥ 0,5–1 pkt, więc wynik jest na granicy zera, a obsunięcie (≈ 51 k USD) jest równe zyskowi.
- **B**: filtr VWAP/SMA200 **pogarsza** wynik (PF 0,98 po kosztach, 0,99 bez kosztów); obsunięcie 99,6 k USD. Druga połowa historii: PF 0,94, −28 k USD.
- Miesiące dodatnie: A 14/27, B 13/27 — moneta.

### Reżim (dzienne zamknięcie vs SMA100, wejście)

| wariant B | n | pkt | średnio |
|---|---|---|---|
| hossa, long | 246 | +2 499 | +10,2 |
| hossa, short | 206 | −2 151 | −10,4 |
| spadek, long | 159 | −1 071 | −6,7 |
| spadek, short | 161 | −327 | −2,0 |

Jedyne, co zarabia, to **long w hossie** (czyli „kup w trendzie wzrostowym" — to samo zrobiłby zwykły buy&hold, tu z kosztami 770 transakcji). Shorty tracą w każdym reżimie w B (w A zarabiają tylko pod SMA100: +2 066 pkt, ale kasują to w hossie: −2 867).

### Pozostałe obserwacje

- B tylko long: PF 1,07, +28,6 k USD, max DD 36,8 k USD — to dopasowanie post factum, bez zysku ponad „jesteśmy w hossie"; US100 w badanym okresie zyskał ok. 45% (18,8k → 27k, szczyt ok. 31k).
- Godzina wejścia: 9:xx NY +3 731 pkt, 10:xx −2 899 pkt, 14:xx −2 041 pkt — rozrzut bez sensownego mechanizmu, typowy szum (nie optymalizujemy po tym).
- Tryb „state" handluje 2,5× częściej i ma PF 0,72 (okno 40 dni) — koszty zjadają wszystko.
- Brak stop-lossa poza odwróceniem: obsunięcia 50–100 k USD na 1 kontrakt NQ to 50–100% rocznego zysku z hossy.

## Wniosek

1. Sam sygnał (A) to ~PF 1,0–1,06 przed kosztami, ≈0 po realnych kosztach.
2. Dokładanie filtrów trendu (B) nie pomaga w skali 25 miesięcy; dobry wynik z ostatnich 40 dni był artefaktem spadku rynku i małej próby.
3. Strategia jest reversal na wskaźnikach opóźnionych (MACD 6/13/5 + HA to podwójne wygładzanie) — typowy „late entry" na 5 min z dużą liczbą transakcji, więc koszty dominują.
4. **Rekomendacja: nie pisać bota.** Jeśli chcemy ją jeszcze ocenić: (a) potwierdzić na prawdziwych futures NQ z poślizgiem z realnego brokera, (b) przetestować tylko intraday-momentum po wybiciu opening range (patrz `ORB` w pamięci: tam jest przewaga, PF 1,6–1,9 dla US100/GER40) zamiast reversal na wskaźnikach.

## Jak odtworzyć

```
python tools/nq_ha_backtest.py --csv tools/.candle_cache/US100_MINUTE_5.csv --days 40
python tools/nq_ha_study.py
```

Wyniki: `tools/out/nq_ha_study_*.csv`, `tools/out/nq_ha_study_equity.png`, transakcje `tools/out/nq_ha_trades_*.csv`. Skrypty: `tools/nq_ha_backtest.py`, `tools/nq_ha_study.py`.
