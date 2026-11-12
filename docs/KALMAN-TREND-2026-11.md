# Kalman Trend Levels [BigBeluga] — kopia ze strzałkami i backtest (2026-11)

Wniosek: **jako stop-and-reverse na crossoverze dwóch linii Kalmana wskaźnik nie ma przewagi na US100/US500/BTC (M15, H1, ostatnie 12 miesięcy). Bota nie piszemy.**

## Wskaźnik

Oryginał: „Kalman Trend Levels [BigBeluga]" (TradingView, licencja CC BY-NC-SA 4.0 — kopia w repo zachowuje nagłówek, autora i licencję; tylko do użytku niekomercyjnego).
- Dwa filtry Kalmana na cenie zamknięcia: krótki (domyślnie 50) i długi (150). Funkcja `kalman_filter(src, length, R = 0.01, Q = 0.1)`: błąd pomiaru `R × length`, szum procesu `Q / length`, więc efektywnie to wygładzanie typu EMA z długością ok. 31 (dla 50) i ok. 95 (dla 150).
- Trend w górę, gdy krótki > długi. Przecięcie rysuje strzałkę z ceną i box poziomu (ATR200/2) wokół świecy sygnału; opcjonalnie „retest" (`x`, `+`).

Kopia `pine/kalman_trend_levels_arrows.pine` (Pine v5, ta sama logika):
- strzałki sygnałów rysowane jako prawdziwe strzałki (`label.style_arrowup/arrowdown`): rozmiar Tiny…Huge (domyślnie Large), pozycja „Candle" (kupno pod dołkiem, sprzedaż nad szczytem) albo na krótkiej linii Kalmana, odległość od świecy w ATR14, kolory, opcjonalny tekst z ceną i jego rozmiar;
- oryginalne małe etykiety z emoji zostają jako opcja, rozmiar znaczków retestu jest regulowany, dodane alerty BUY/SELL, zabezpieczenie przed odwołaniem do nieistniejącego boxa przy retestach.
- Nie kompilowane w TradingView w tym repo — po wklejeniu sprawdzić, czy edytor nie zgłasza błędów.

## Backtest

`tools/kalman_fetch.py` (dane), `tools/kalman_trend_bt.py` (test). Dokładny port funkcji Kalmana z Pine. Dane: świece Capital.com (mid), M15 i H1, US100, US500, BTCUSD, ostatnie 12 miesięcy (2025-11-12 … 2026-11-12), dodatkowo okres rozgrzewki.
Strategia: zawsze w rynku, long gdy krótki > długi, short w przeciwnym razie; sygnał na zamknięciu świecy z przecięciem, wejście na otwarciu następnej; koszt na stronę 0,01% (indeksy) i 0,04% (BTC); jedna jednostka nominału, zwroty sumowane. Siatka: krótka 10/20/30/50/75 × długa 100/150/200/300/400 (krótka < długa), 25 kombinacji na instrument i interwał. Retesty nie są handlowane.

Wyniki (suma zwrotów po kosztach, %):

| | kup-i-trzymaj | kombinacje dodatnie | mediana | domyślne 50/150 | dodatnie w obu połowach roku |
|---|---|---|---|---|---|
| US100 H1 | +10,9 | 15/25 | +3,9 | +12,8 (PF 1,29) | 52% |
| US100 M15 | +11,1 | 14/25 | +1,0 | +10,1 (PF 1,11) | 8% |
| US500 H1 | +10,9 | **0/25** | −10,9 | −21,9 (PF 0,53) | 24% |
| US500 M15 | +11,1 | **0/25** | −13,6 | −12,8 (PF 0,84) | 0% |
| BTC H1 | −29,6 | 2/25 | −30,0 | −46,5 (PF 0,72) | 4% |
| BTC M15 | −29,3 | 0/25 | −49,5 | −53,3 (PF 0,82) | 0% |

Obserwacje:
1. **US100 vs US500:** oba indeksy są silnie skorelowane (ten sam rok, +11%), a dokładnie ta sama strategia na US100 wychodzi na plus w ok. połowie siatki, na US500 w żadnej. To znak, że plusy na US100 to w dużej mierze przypadek (kombinacje, którym akurat pasowały te wahania), a nie przewaga.
2. **BTC:** w roku spadkowym (−30%) strategia traci jeszcze więcej niż kup-i-trzymaj (shorty też tracą), przy skuteczności 25–26% i dużej liczbie transakcji (do 1 000 na M15).
3. **Skuteczność transakcji 25–39%**, typowy system oparty na przecięciu średnich: dużo piłowania w konsolidacji, kilka dłuższych trendów ma to pokryć. Przy M15 koszt (spread + poślizg) zjada tym więcej, im więcej transakcji (przed kosztami mediana lepsza o 5–35 pkt proc.).
4. **Dobór parametrów na pierwszej połowie roku** i test na drugiej: US100 H1 50/100 → 2. połowa +21,6%, ale to jedna z 25 kombinacji na jednej serii; US500 i BTC M15 nie potwierdzają. Brak stabilnej „dobrej" pary.
5. Strona longowa na US100 jest dodatnia (np. +12,7% przy 50/150), strona shortowa ≈ 0 — czyli to głównie beta rynku akcji, nie sygnał.

## Analiza: czy robić bota

**Nie.** Powody: brak spójnej przewagi między US100, US500 i BTC, wyniki zależą od wybranej pary długości, a strategia handluje 50–1 000 razy w roku z dużym piłowaniem. Czyli to wskaźnik wizualny (kolor trendu, strzałki, box poziomu), nie system z przewagą.

Ograniczenia testu: jeden rok, zwroty sumowane bez dźwigni i bez reinwestycji, koszty uproszczone (0,01% / 0,04% na stronę), tylko sygnał przecięcia (bez retestów, bez stopów na boxach, bez filtrów).

Jeśli chcemy to jeszcze rozwijać: (a) test na wyższych interwałach (H4/D) i dłuższej historii (US100 D1 mamy od 2010), (b) tylko long jako filtr trendu dla innej strategii (np. ladderów) zamiast osobnego bota, (c) wykorzystać retesty boxów jako wejścia (nietestowane).
