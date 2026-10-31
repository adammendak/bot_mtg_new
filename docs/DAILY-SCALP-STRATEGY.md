# Daily Scalp — 1 wejście dziennie, mały TP, failsafe zamiast SL

Stan na 31.10.2026. Cel użytkownika: forward test **30 dni na ETH i SOL** oraz **20 dni roboczych na NQ (US100) i SP500 (US500)**, po 1 wejściu dziennie na każdym instrumencie osobno, bardzo małe ruchy (TP ok. 20–30 zł), bez SL, docelowo **6% miesięcznie** (np. 30 wejść × 0.2%).

Ten dokument opisuje, co z tego da się uczciwie zbudować, co pokazał backtest i jak ma wyglądać forward test. Powiązane pliki: [`tools/daily_scalp_bt.py`](../tools/daily_scalp_bt.py) (silnik), [`tools/daily_scalp_report.py`](../tools/daily_scalp_report.py) (raport), [`pine/daily_scalp_signals.pine`](../pine/daily_scalp_signals.pine) (wskaźnik + alerty), [`docs/DAILY-SCALP-RESULTS.md`](DAILY-SCALP-RESULTS.md) (wszystkie tabele).

## 1. Werdykt w pięciu punktach

1. **6% miesięcznie przy małym ryzyku jest matematycznie nieosiągalne.** Przy 1:1 miesięczny wynik to `n × f × (2p − 1)`. Dla 30 wejść i ryzyka 0.2% na wejście trzeba **100% trafności**; przy 0.33% trzeba 80%, przy 0.5% 70%, przy 1% 60%. Wszystko poniżej 100% przy 0.2% daje najwyżej ułamek procenta. „0.2 × 30 = 6%" zakłada, że wygrywa każde wejście.
2. **Przed kosztami sygnały nie mają przewagi.** Średnio po wszystkich sygnałach: −0.02R (ETH), −0.01R (SOL), −0.01R (NQ), 0.00R (SP500) na wejście. Po kosztach: crypto −0.18R, indeksy −0.06…−0.07R. Sygnały nie wyróżniają się od losowych wejść (kontrola).
3. **„TP bez SL" daje wysoką trafność (70–95%) i ogromne straty w ogonie.** Najgorsze pojedyncze wejścia bez żadnego zabezpieczenia: −55…−110 D (D = odległość TP), czyli przy 0.2% ryzyka na D to strata rzędu 11–22% konta jednym wejściem. Dlatego w każdej wersji potrzebny jest failsafe.
4. **Kandydaci istnieją tylko na indeksach**, ze słabą przewagą (+0.24…+0.39R na wejście, t = 1.8…3.4) i ~5–10 wejść miesięcznie, a nie 20–30. Na ETH i SOL żadna konfiguracja nie przeszła zarówno testu in-sample, jak i out-of-sample z sensowną istotnością.
5. **Realistyczne oczekiwanie:** ok. **+0.5…+1.5% miesięcznie** przy maksymalnej stracie pojedynczego wejścia 0.8–1.6% konta, a nie 6%. Wyższy wynik wymaga ryzyka (i rozstępu drawdownu) rzędu 10–20% miesięcznie.

## 2. Co jest w sieci (krótki przegląd)

Nie znalazłem niezależnego, rzetelnego badania dokładnie tego układu (1 wejście dziennie, stały mały TP, pivoty plus RSI/Stoch, NQ/ES). Wszystko, co jest dostępne, to materiały praktyków i marketing, więc traktuję je jako tropy, nie dowody:

- **Strategie „TP bez SL"** reklamują wysoką trafność (np. 85%+ po usunięciu stopa), ale zwykle nie pokazują wielkości przegranych ([NexusFi](https://nexusfi.com/showthread.php?p=816966)). To dokładnie ogon, który mierzę w sekcji 4.
- **Ryzyko ruiny** zależy od trafności, stosunku zysku do straty i ryzyka na wejście, a wzory analityczne zaniżają je, bo ignorują serie strat; lepsza jest symulacja Monte Carlo na prawdziwych seriach wejść ([Backtrex](https://backtrex.com/en/blog/monte-carlo-risk-of-ruin-trading-backtest), [kalkulator ryzyka ruiny](https://www.liberatedstocktrader.com/risk-of-ruin-calculator/)). Robię to w sekcji 5.
- **Tarcie obniża przewagę:** im dalej TP, tym rzadsze trafienie, a koszty podnoszą wymaganą trafność ([CrossTrade](https://crosstrade.io/blog/your-tp-sl-ratio-is-not-a-strategy)).
- **Pivoty intraday:** badanie QuantInsti wskazuje na przeciętne wyniki strategii na pivotach, a odwracanie do średniej wypada gorzej niż momentum ([QuantInsti](https://blog.quantinsti.com/pivot-point-strategy/)). Mój backtest pokazuje pivoty jako najmniej złą rodzinę, ale tylko na NQ.
- **Market Cipher / WaveTrend:** jedyny test obejmujący ETH i SOL, jaki znalazłem, to własny, niezweryfikowany test autora jednego ze skryptów Cipher B na TradingView: surowe sygnały użyte mechanicznie nie biły losowych wejść. Tło o wskaźniku: [Gainium](https://gainium.io/blog/vumanchu-cipher-b-indicator-explained). Mój wynik jest zgodny (tabela sygnałów a kontroli w sekcji 4).

## 3. Metoda backtestu

- **Dane:** ETH i SOL perpetual z OKX, M15, 17.08.2025–31.10.2026 (42 260 świec); US100 i US500 z Capital (ceny mid), M5, 11.09.2024–30.10.2026 (ok. 155 tys. świec). Podział czasowy 60/40: in-sample do 8.05.2026 (crypto) i 23.12.2025 (indeksy), reszta to out-of-sample.
- **Wejście:** sygnał liczony na **zamkniętych** świecach H1 lub H4, wejście na otwarciu następnej świecy bazowej (bez lookaheadu). Maksymalnie **1 wejście dziennie** na instrument. Indeksy: okno wejść 13:30–19:30 UTC (sesja USA), siłowe zamknięcie 21:00 UTC. Crypto: cała doba UTC.
- **TP:** D = 0.30–1.50% (crypto) i 0.10–0.50% (indeksy), stały procent ceny.
- **Modele wyjścia:** `E1` 1:1 z SL = D; `E2` samo TP, zamknięcie na końcu dnia; `E3_FS3/FS5` TP + failsafe na 3D/5D + koniec dnia; `E4_TP_3D` samo TP, trzymanie do 3 dni; `E4_FS8` TP + failsafe 8D + do 3 dni.
- **Sygnały:** `RSI_LEVEL` (≤30 / ≥70), `RSI_CROSS` (powrót przez 30 / 70), `STOCH_CROSS` (K przecina D w strefach 25/75), `WT_CROSS` (WaveTrend z Market Cipher B, strefy ±45), `PIVOT_BOUNCE` (dotknięcie S1/R1 poprzedniego dnia i powrót), `PIVOT_BREAK` (przecięcie pivota P), `COMBO_OSC_PIVOT`, `TREND_PULLBACK` oraz trzy **kontrole bez umiejętności**: wejście w kierunku wczoraj (`CTRL_MOMENTUM`), przeciwnie (`CTRL_FADE`) i losowe (`CTRL_RANDOM`).
- **Koszty:** crypto 0.10% round-trip (OKX taker plus poślizg), indeksy 0.012%. Test wrażliwości: 0×, 1×, 2×.
- **Niejednoznaczna świeca** (TP i stop w jednej): liczony stop (konserwatywnie).
- **Skala testu:** 2 160 konfiguracji. Przy tylu testach część „wygrywa" sama z siebie, dlatego wynik oceniam out-of-sample i porównuję z kontrolami.

## 4. Wyniki — skrót (pełne tabele w [DAILY-SCALP-RESULTS.md](DAILY-SCALP-RESULTS.md))

**Średnia po wszystkich sygnałach i odległościach D, netto po kosztach (jednostki R = D):**

| Instrument | 1:1 z SL | TP do końca dnia | TP + failsafe 8D (≤3 dni) | TP do 3 dni, bez zabezpieczenia | Najgorsze wejście bez zabezpieczenia |
|---|--:|--:|--:|--:|--:|
| ETH | −0.19 | −0.30 | −0.24 | −0.33 | −110 D |
| SOL | −0.18 | −0.16 | −0.23 | −0.34 | −82 D |
| NQ | −0.10 | −0.07 | −0.10 | −0.17 | −74 D |
| SP500 | −0.08 | −0.05 | −0.02 | −0.04 | −56 D |

**Wrażliwość na koszty (średnia R, 1:1 i failsafe 5D):** ETH −0.02 / −0.19 / −0.36 przy kosztach 0× / 1× / 2×; NQ −0.01 / −0.07 / −0.14; SP500 0.00 / −0.06 / −0.13. Przewaga brutto praktycznie zero.

**Sygnały a kontrole (średnia R, wszystkie konfiguracje):** WaveTrend −0.07, pivot-bounce −0.07, pivot-break −0.10, RSI-cross −0.13, STOCH −0.21, RSI_LEVEL −0.28 vs kontrole: fade wczorajszego ruchu −0.12, momentum −0.16, losowe −0.17. **Nic nie wyróżnia się wyraźnie od losowych wejść**, a „łapanie noża" (RSI na poziomie, Stoch) jest gorsze od losowych.

**Wybór zakresu D:** na crypto im dalszy TP, tym mniejsza strata (koszt 0.10% zjada TP 0.3% w jednej trzeciej: średnio −0.39R przy 0.3% vs −0.07R przy 1.5%). Na indeksach różnica jest mniejsza; najmniej złe są D = 0.30–0.50%.

**H1 czy H4:** na indeksach H4 jest nieco mniej ujemne, ale daje tylko ok. 4 sygnały miesięcznie (H1: ok. 8). Wszystkie konfiguracje, które przeszły test, to H1.

**Kto przeszedł in-sample i out-of-sample (t ≥ 1.5, n ≥ 60):** 24 z 2 160. To rząd wielkości tego, co przechodzi z samego przypadku przy tylu testach (szacunek: kilkanaście do trzydziestu), więc samo przejście testu niczego nie dowodzi. Skupiska:

| Kandydat | n | Trafność | Śr. R | t | Najgorsze wejście | Kwartały na plusie |
|---|--:|--:|--:|--:|--:|--:|
| **NQ**, pivot-bounce H1, TP 0.30%, failsafe 8D, ≤3 dni | 260 | 87% | +0.24 | 1.8 | −8.0 D | 8/10 |
| NQ, pivot-bounce H1, TP 0.30%, **bez** failsafe | 260 | 88% | +0.39 | 3.4 | −11.4 D | 10/10 |
| **SP500**, RSI-cross H1, TP 0.15%, failsafe 8D, ≤3 dni | 138 | 89% | +0.34 | 2.1 | −8.1 D | 8/10 |
| SP500, fade wczorajszego ruchu (kontrola), TP 0.30%, failsafe 8D | 551 | 80% | +0.08 | 0.9 | −8.0 D | 7/10 |
| SOL, pivot-bounce H1, TP 0.75%, failsafe 8D | 280 | 92% | +0.20 | 1.4 | −8.1 D | 4/6 |
| ETH, pivot-bounce H1, TP 0.75%, failsafe 5D | 266 | 72% | −0.18 | −1.5 | −5.1 D | 0/6 |

SP500 RSI-cross to głównie **longi** (+0.68R vs shorty +0.11R), czyli częściowo dryf wzrostowy rynku w latach 2024–26. Fade wczorajszego ruchu jako kontrola nie ma przewagi z failsafe (t 0.9), a bez failsafe jest dodatni tylko dzięki ogonowi.

**Odpowiedzi na pytania z zadania:**

- **Dołki godzinne czy 4-godzinne?** H1. H4 daje za mało wejść i nie jest lepszy.
- **RSI, Stoch czy Market Cipher?** RSI **powrót przez 30** (nie sam poziom) ma sens tylko na SP500 po stronie long. Stoch nie działa. WaveTrend (rdzeń Market Cipher B) jest tylko trochę mniej zły od losowych wejść i na crypto nie ma przewagi.
- **Po pivotach?** Tak: odbicie od **S1/R1 poprzedniego dnia na H1** to jedyny sygnał ze spójnym wynikiem na NQ (10 kwartałów z 10 bez failsafe, 8 z 10 z failsafe). Na ETH nie działa.
- **Jaki zakres?** NQ: TP **0.30%** (ok. 85–90 pkt przy 29 000; testowane 0.10–0.50%). SP500: TP **0.15%** (testowane 0.10–0.50%). SOL: TP **0.75%**. ETH: brak działającego zakresu (D ≥ 0.75%, żeby koszty nie zjadały TP).

## 5. Ryzyko: ile można zarobić i ile stracić

Notacja: `f` = % konta zyskiwany po trafieniu TP (czyli ryzyko „na D"). Strata przy failsafe = 8f. Wyniki z 2 000 symulowanych miesięcy (bootstrap realnych wejść, 20 wejść na indeksach, 30 na crypto) dla kandydatów z failsafe 8D:

| Kandydat | f | Średni miesiąc | 5. percentyl | P(≥6%) | P(≤−5%) | DD 95. percentyl | Najgorsze wejście |
|---|--:|--:|--:|--:|--:|--:|--:|
| NQ pivot-bounce H1 | 0.20% | **+0.9%** | −2.5% | 0% | 1% | 3.9% | −1.6% |
| NQ pivot-bounce H1 | 0.33% | +1.5% | −4.2% | 6% | 4% | 6.5% | −2.7% |
| NQ pivot-bounce H1 | 0.50% | +2.3% | −6.3% | 22% | 7% | 9.8% | −4.0% |
| NQ pivot-bounce H1 | 1.00% | +4.5% | −12.6% | 49% | 15% | 19.7% | −8.0% |
| SP500 RSI-cross H1 | 0.20% | **+1.4%** | −1.9% | 0% | 0% | 3.3% | −1.6% |
| SP500 RSI-cross H1 | 0.33% | +2.2% | −3.1% | 10% | 2% | 5.4% | −2.7% |
| SP500 RSI-cross H1 | 0.50% | +3.4% | −4.7% | 29% | 4% | 8.2% | −4.0% |
| SOL pivot-bounce H1 | 0.20% | +1.2% | −3.8% | 0% | 2% | 5.3% | −1.6% |
| SOL pivot-bounce H1 | 0.50% | +3.0% | −9.5% | 36% | 9% | 13.2% | −4.1% |
| ETH pivot-bounce H1 | 0.20% | **−1.1%** | −4.7% | 0% | 4% | 5.5% | −1.0% |

Wniosek liczbowy: żeby średnio dobić do 6% miesięcznie, trzeba `f` ≈ 1.3% na D, co daje drawdown (95. percentyl) powyżej 20% i stratę ok. 10–11% konta jednym failsafe. **To nie jest „małe ryzyko".** Przy `f` = 0.2% realny oczekiwany wynik to ok. +1% miesięcznie.

Dwie uwagi o próbie: kandydaci wywodzą się z 2 160 testów (zawyżenie), a okres 2024–2026 to hossa na indeksach (zawyżenie dla longów). Prawdziwy wynik może być gorszy.

## 6. Failsafe — warstwy zabezpieczeń

Warstwy działają razem; żadna z nich nie jest „SL" z 1:1, ale każda ogranicza ogon:

1. **Twardy failsafe na 8 × D od ceny wejścia** (stop awaryjny po stronie brokera, ustawiony od razu przy wejściu). Obcina najgorsze wejście z −55…−110 D do −8 D.
2. **Limit czasu:** pozycja zamykana po 3 dniach lub o 21:00 UTC (indeksy), nigdy bez terminu.
3. **Wyłącznik miesięczny:** przy skumulowanej stracie miesiąca ≤ −3% koniec wejść do końca miesiąca (w symulacji dla NQ obcina P(miesiąc ≤ −5%) z 4% do 1% przy f = 0.33% i z 7% do 4% przy f = 0.5%).
4. **Stały rozmiar pozycji:** `f` ustalone z góry, bez zwiększania po stracie, bez uśredniania pozycji.
5. **Jedna pozycja na instrument** i łączne ryzyko failsafe wszystkich otwartych pozycji ≤ 2% konta.
6. **Pauza po serii:** 3 failsafe w ciągu 10 wejść wstrzymują wejścia na dany instrument do ręcznego przeglądu.
7. **Okna handlowe:** bez wejść w przerwie technicznej Capital (20:59:50–21:05 UTC) i w weekend na indeksach.

**Rozmiar w PLN (przykład konta 10 000 zł):** `f` = 0.2% = 20 zł zysku z TP; failsafe 8D = 160 zł (1.6%). Pozycja w jednostkach ≈ `f × konto / (D% × cena)`. Dla NQ przy D = 0.30% oznacza to nominał ok. 6 700 zł, czyli ruch o 0.30% = 20 zł.

## 7. Specyfikacja strategii do forward testu („Daily Scalp v0")

| | NQ (US100) | SP500 (US500) | SOL | ETH |
|---|---|---|---|---|
| Status | kandydat | kandydat | **eksploracyjny** (słaba przewaga, t 1.4) | **kontrola** (brak przewagi) |
| Sygnał (H1, zamknięta świeca) | odbicie od S1 (long) / R1 (short) poprzedniego dnia | RSI(14) przecina 30 w górę (long) / 70 w dół (short) | odbicie od S1/R1 | odbicie od S1/R1 |
| Okno wejść (UTC) | 13:30–19:30, pn–pt | 13:30–19:30, pn–pt | cała doba | cała doba |
| TP | 0.30% | 0.15% | 0.75% | 0.75% |
| Failsafe | 8 × TP = 2.4% | 8 × TP = 1.2% | 8 × TP = 6% | 5 × TP |
| Limit czasu | 3 dni, 21:00 UTC | 3 dni, 21:00 UTC | 3 dni | koniec dnia |
| `f` (zysk z TP, % konta) | 0.20% | 0.20% | 0.10% | 0.10% |
| Oczekiwana liczba wejść | ok. 9 / 20 dni | ok. 5 / 20 dni | ok. 19 / 30 dni | ok. 18 / 30 dni |

**Uwaga o „1 wejściu dziennie":** sygnały nie odpalają każdego dnia, więc 20 wejść na indeksach nie wyjdzie; spodziewane jest ok. 9 (NQ) i ok. 5 (SP500) w 20 dniach roboczych. Wymuszanie wejścia codziennie (fade wczorajszego ruchu) nie ma przewagi z failsafe.

**Kryteria oceny forward testu (zapisane z góry):**

- Sukces: po min. 20 wejściach na instrument śr. R > +0.10 po kosztach, trafność ≥ 75% i **zero** trafień failsafe w pierwszych 10 wejściach.
- Przerwanie natychmiast: strata skumulowana ≤ −3% konta, albo 2 failsafe w 10 wejściach, albo odrzucenia zleceń psujące wyniki (np. przerwa 21:00 UTC).
- Rozstrzygnięcie: min. 30 wejść łącznie na instrument; poniżej — brak wniosków.
- ETH jest grupą kontrolną: oczekiwany wynik ≤ 0; jeśli wyjdzie dodatni, to dowód na szum, a nie na edge, dopóki nie powtórzy się out-of-sample.

## 8. Implementacja i co dalej

- **Silnik badawczy:** `python tools/daily_scalp_bt.py --cache <cache> --out tools/out/daily_scalp` (ok. 30 s na pełną siatkę), potem `python tools/daily_scalp_report.py --cache <cache> --results tools/out/daily_scalp`. Parametry (D, koszty, okna, sygnały) są na górze pliku. Koszty: `--cost-mult 0|2`.
- **Wskaźnik Pine:** [`pine/daily_scalp_signals.pine`](../pine/daily_scalp_signals.pine) rysuje sygnały, TP i failsafe, liczy statystykę R i wysyła alerty w formacie webhooka bota (`event: entry` z `sl` = failsafe i `tp`; `tp` przy trafieniu; `invalid` przy failsafe/limicie czasu). Nie był kompilowany w TradingView w tej sesji, więc przy pierwszym dodaniu sprawdź kompilację.
- **Bot:** istniejący webhook (`/api/tv/signal`) przyjmuje już pola `sl` i `tp`, ale egzekucja jest dziś tylko dla strategii `algopro-*`. Do forward testu na kontach demo potrzeba osobnych kont (jak dla AlgoPro) i obsługi `scalp-*` (rozmiar z `f`, broker-side failsafe, limit czasu i wyłącznik miesięczny). To następny krok po Twojej decyzji, które instrumenty uruchamiamy.
- **Ograniczenia:** wyniki z danych mid (Capital) i perpetuali OKX z założonymi kosztami; brak modelu poślizgu w momencie gwałtownych ruchów; próba 14.5 miesiąca (crypto) i 25 miesięcy (indeksy); wynik wyselekcjonowany z 2 160 testów.
