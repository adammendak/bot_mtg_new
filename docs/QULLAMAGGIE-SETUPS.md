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

Tabela: miejsce w jednym z 9 pól ekranu (każdy róg, środki krawędzi, środek) i rozmiar Tiny…Huge (grupa Display). Wiersze informacyjne z danych dziennych (niezależnie od interwału wykresu): **ADR%** (w nawiasie to samo w jednostkach ceny), **ATR(14d)** (cena i % ceny) oraz **odległość od EMA50 dziennej** w % i w wielokrotnościach ADR (np. +6,2% = +1,1 ADR; zielone nad EMA, czerwone pod).

**Setup gotowy (tło aqua + linia „pivot")** gdy jednocześnie:
- ADR% ≥ 5,
- poprzedni wzrost ≥ 30% w 63 sesjach, z dołkiem przed szczytem,
- trend: MA10 > MA20 > MA50, MA10 i MA20 rosną, cena nad MA50,
- konsolidacja z ostatnich 10 sesji: zakres ≤ 2,5 × ADR, cofnięcie od szczytu biegu ≤ 25%, wyższe dołki (ostatnie 5 sesji vs poprzednie 5), średni wolumen 5 dni < średni 20 dni.

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
- Progi (30%, 2,5 × ADR, 1,5 × wolumen itd.) są naszą interpretacją. Zanim ktokolwiek handluje według tego, trzeba je sprawdzić backtestem na liście akcji (w repo mamy tylko dzienne US100, QQQ, TQQQ; akcji pojedynczych brak) i potwierdzić, że wskaźnik kompiluje się w TradingView.
- Nie piszemy na to bota: Capital.com handluje CFD na akcje z innymi kosztami, a strategia opiera się na wyborze liderów ze skanera.

Źródła: opisy wskaźników TradingView (m.in. „Qullamaggie Daily with ADR% and Compression Range", „Qullamaggie Breakout V2"), arkusz badawczy Complete Trader's Edge o Kristjanie Kullamägim, artykuł forex.in.rs „Kristjan Kullamägi Stocks Trading Strategy".
