# Technika Qullamaggie (Kristjan Qullamägi) i wskaźnik `pine/qullamaggie_setups.pine`

Opis oparty na źródłach wtórnych (opisy wskaźników TradingView, blogi, arkusz badawczy) — nie na jego własnym podręczniku.
Liczby w wskaźniku to **nasze domyślne wartości do strojenia**, nie jego oficjalne reguły. Wskaźnik nie był testowany na wykresie TradingView ani backtestowany w tym repo.

## Co to jest ADR

**ADR = Average Daily Range**, średni dzienny zakres ruchu ceny. Mówi, jak bardzo instrument „pracuje" w ciągu jednej sesji.
- Wersja procentowa używana w społeczności Qullamaggie: `ADR% = 100 × (SMA(high / low, 20) − 1)`. Czyli średnia z ostatnich 20 dni z relacji high do low, w procentach. Wskaźnik w repo liczy dokładnie to (zawsze z danych dziennych). Dokładnej formuły z jego własnych materiałów nie potwierdziłem.
- Inne warianty: `(high − low) / poprzedni close × 100` z jednego dnia albo ADR w jednostkach ceny.
- Do czego służy: filtr akcji „szybko poruszających się" — zwykle ADR% ≥ 5–6 (tak podają opisy). Używany też do rozmiaru ryzyka: stop z reguły nie większy niż 1 × ADR.
- Uwaga: jeden z blogerów nie znalazł korelacji między wysokim ADR% a swoimi zwycięskimi transakcjami (mała próba), więc to filtr „potencjału zysku", nie gwarancja.

## Na czym polega technika

Swing trading akcji wzrostowych (liderów rynku) z krótkimi stopami i dużymi zwycięzcami. Strategia działa dzięki temu, że wygrane są wielokrotnie większe od strat; skuteczność pojedynczych wejść jest poniżej 50%, fałszywych wybić jest dużo.

Trzy setupy:

1. **Breakout (wybicie)** — główny. Akcja po silnym wzroście (30–100%+ w 1–3 miesiące) wchodzi w uporządkowaną konsolidację: coraz węższy zakres, wyższe dołki, wolumen wysycha, cena trzyma się przy rosnących średnich 10/20 dni. Wejście przy wybiciu górnej granicy konsolidacji (często intraday, np. wybicie opening range) na podwyższonym wolumenie. Stop na dołku dnia wybicia.
2. **Episodic Pivot (EP)** — gap w górę (rzędu 10%+) na ogromnym wolumenie po ważnej wiadomości, na akcji, która wcześniej była „zaniedbana" (bez dużego wzrostu). Opis pochodzi z metody Pradeepa Bondego (Stockbee). EP brane w trendzie spadkowym często są wybijane ze stopa.
3. **Parabolic short** — tylko po stronie krótkiej: akcja po paraboli (ogromny wzrost w kilka dni) i daleko nad średnią; cel: powrót do 10/20 dniowej średniej. Opisywane jako ryzykowne, w naszym wskaźniku tylko flaga do obserwacji.

Zarządzanie pozycją: po 3–5 dniach sprzedaje się część pozycji (1/3–1/2) i stop przesuwa na break-even; resztę prowadzi się trailing stopem — zamknięcie poniżej średniej 10 lub 20 dni = wyjście.

## Potrzebne wskaźniki

| Co | Po co |
|---|---|
| Średnie 10 / 20 / 50 dni (SMA lub EMA; 200 opcjonalnie) | trend (10 > 20 > 50, rosnące), trailing stop (zamknięcie pod 10 lub 20), „nie kupuj pod 50" |
| ADR% (20 dni) | wybór szybkich instrumentów, ocena stopa względem ADR |
| Wolumen + jego średnia 20 dni | wysychanie w konsolidacji, ekspansja przy wybiciu i EP |
| Siła relatywna / ranking wzrostów 1-3-6 miesięcy | wybór liderów (w skanerze, nie w wskaźniku) |
| Płynność (obrót dolarowy) | wykluczenie niepłynnych spółek (skaner) |

## Nasz wskaźnik: `pine/qullamaggie_setups.pine` (Pine v6, wykres dzienny)

Rysuje średnie, liczy ADR% z danych dziennych i pokazuje tabelę stanu setupu. Wszystkie progi są parametrami.

**Setup gotowy (tło aqua + linia „pivot")** gdy jednocześnie:
- ADR% ≥ 5,
- poprzedni wzrost ≥ 30% w 63 sesjach, z dołkiem przed szczytem,
- trend: MA10 > MA20 > MA50, MA10 i MA20 rosną, cena nad MA50,
- konsolidacja z ostatnich 10 sesji: zakres ≤ 5 × ADR (2,5 było za ciasne, patrz backtest), cofnięcie od szczytu biegu ≤ 25%, wyższe dołki (ostatnie 5 sesji vs poprzednie 5), średni wolumen 5 dni < średni 20 dni.

**Sygnały:**
- `BO` — zamknięcie nad szczytem konsolidacji, wolumen ≥ 1,5 × średnia, świeca wzrostowa. Rysuje stop (dołek dnia wybicia) i etykietę z ryzykiem; ostrzega, gdy ryzyko > 1 × ADR.
- `EP` — gap ≥ 10%, wolumen ≥ 3 × średnia, świeca wzrostowa, poprzednie 20 sesji bez dużego wzrostu (≤ 30%).
- `1/3` — częściowa realizacja po 3 sesjach, jeśli pozycja jest nad ceną wejścia.
- `EXIT` — zamknięcie pod średnią trailing (MA fast = 10 lub MA mid = 20) albo pod stopem.
- Fioletowy trójkąt — parabola (obserwacja, bez wejść): wzrost ≥ 50% w 10 sesjach i cena ≥ 3 × ADR nad MA20.

Alerty: Breakout, Episodic pivot, Setup ready, Exit.

## Ograniczenia i następne kroki

- Skaner (płynność, ranking siły relatywnej) nie jest częścią wskaźnika. Wskaźnik ocenia jeden instrument naraz.
- Wejście na zamknięciu dnia to uproszczenie; on często wchodzi intraday na wybiciu opening range.
- Progi (30%, 5 × ADR, 1,5 × wolumen itd.) są naszą interpretacją; wynik backtestu poniżej. Wskaźnik trzeba jeszcze sprawdzić w edytorze TradingView (kompilacja).
- Nie piszemy na to bota: Capital.com handluje CFD na akcje z innymi kosztami, a strategia opiera się na wyborze liderów ze skanera.

Źródła: opisy wskaźników TradingView (m.in. „Qullamaggie Daily with ADR% and Compression Range", „Qullamaggie Breakout V2"), arkusz badawczy Complete Trader's Edge o Kristjanie Kullamägim, artykuł forex.in.rs „Kristjan Kullamägi Stocks Trading Strategy".

## Backtest (2026-11): brak przewagi, bota nie piszemy

Skrypty: `tools/qullamaggie_fetch.py` (pobranie), `tools/qullamaggie_bt.py` (backtest portfelowy), lista `tools/qullamaggie_universe.txt`.
Dane: 199 akcji z Capital.com (dzienne, 2020-11 … 2026-11; 20 z 220 tickerów nie ma na Capital.com). Wolumen to wolumen CFD z Capital.com, nie giełdowy.
Reguły: sygnał na zamknięciu dnia t−1, wejście na otwarciu t (+0,05% poślizgu), stop = dołek dnia wybicia, po 3 sesjach 1/3 pozycji i stop na break-even, reszta wychodzi po zamknięciu poniżej MA10; ryzyko 0,5% kapitału na transakcję, max 8 pozycji, max 25% kapitału na pozycję, bez dźwigni; koszt finansowania CFD 7% rocznie.

Wnioski:
1. **Domyślne progi były za ciasne**: z zakresem konsolidacji ≤ 2,5 × ADR wychodziła 1 transakcja w 6 lat na 199 spółek. Dla 10 sesji typowy zakres to 3–6 × dzienny ADR, więc próg podniesiony do 5 (Pine też).
2. Po poluzowaniu: 15–552 transakcji zależnie od wariantu, skuteczność ok. 30–40%, średnia wygrana 3–6 R, średnia strata −1,4…−1,6 R (luki i poślizg pogarszają założone −1 R).
3. Profit factor: **0,75–1,65**, większość wariantów 0,93–1,12. Najlepszy (V2: konsolidacja ≤ 5 × ADR, bez warunków wyższych dołków i wysychania wolumenu, wolumen wybicia ≥ 1,5 × średnia, 62 transakcje): PF 1,65, +20% łącznie przez 6 lat (3,2% rocznie), ale **5 najlepszych transakcji daje 162% całego zysku** (głównie 2024: +22 tys. z 100 tys.), w 2023, 2025 i 2026 wynik ujemny. To jeden epizod, nie powtarzalna przewaga.
4. Dla porównania: równo ważony kup-i-trzymaj tych spółek +196% (maks. obsunięcie 41%), US100 +117%. Filtr rynku (US100 > SMA50) nie pomaga (PF 0,99). Pełny zestaw reguł z Pine (z wyższymi dołkami i wysychaniem wolumenu): 15 transakcji, PF 0,75.
5. Ograniczenia testu: lista spółek ułożona dziś (survivorship bias zawyża wyniki, a przewagi i tak nie ma), wejście na zamknięciu zamiast intraday (on kupuje wybicie w trakcie dnia), brak rankingu siły relatywnej i filtra płynności z oryginalnego skanera. Intraday-owa wersja albo prawdziwy skaner mogą wyglądać lepiej, ale tego nie testowaliśmy.

Rekomendacja: nie budować bota na akcje. Zostaje wskaźnik jako pomoc przy ręcznym wyborze. Jeśli ma powstać codzienny skaner, to jako raport mailowy („co dziś spełnia setup") do ręcznej decyzji, bez automatycznych zleceń.
